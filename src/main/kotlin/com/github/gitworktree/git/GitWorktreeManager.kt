package com.github.gitworktree.git

import com.github.gitworktree.model.WorktreeInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class GitWorktreeResult(
    val exitCode: Int,
    val output: String,
    val errorOutput: String,
) {
    fun success(): Boolean = exitCode == 0
    val errorOutputAsJoinedString: String get() = errorOutput.trim()
}

internal enum class RequiredNewBranchReason {
    MISSING_LOCAL_BRANCH,
    LOCAL_BRANCH_OCCUPIED,
    LOCAL_BRANCH_CANNOT_FAST_FORWARD,
}

internal data class WorktreeSourceResolution(
    val source: String,
    val newBranch: String? = null,
    val remoteSourceToFetch: String? = null,
    val localBranchToFastForward: String? = null,
    val requiredNewBranchReason: RequiredNewBranchReason? = null,
)

internal data class RemoteBranchResolutionCheck(
    val resolution: WorktreeSourceResolution? = null,
    val errorMessage: String? = null,
)

internal fun resolveWorktreeSource(
    source: String,
    requestedNewBranch: String?,
    localBranchNames: Set<String>,
    remoteBranchNames: Set<String>,
    occupiedBranchNames: Set<String> = emptySet(),
    canFastForwardExistingLocalBranch: Boolean = true,
): WorktreeSourceResolution {
    if (requestedNewBranch != null) {
        return WorktreeSourceResolution(
            source = source,
            newBranch = requestedNewBranch,
            remoteSourceToFetch = source.takeIf { it in remoteBranchNames },
        )
    }

    if (source in localBranchNames && source in occupiedBranchNames) {
        return WorktreeSourceResolution(
            source = source,
            newBranch = suggestLocalBranchName(source, localBranchNames),
            requiredNewBranchReason = RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED,
        )
    }

    if (source !in remoteBranchNames) {
        return WorktreeSourceResolution(source = source)
    }

    val implicitLocalBranch = source.substringAfter("/", source).ifBlank { source }
    if (implicitLocalBranch !in localBranchNames) {
        return WorktreeSourceResolution(
            source = source,
            newBranch = implicitLocalBranch,
            remoteSourceToFetch = source,
            requiredNewBranchReason = RequiredNewBranchReason.MISSING_LOCAL_BRANCH,
        )
    }

    if (implicitLocalBranch in occupiedBranchNames) {
        return WorktreeSourceResolution(
            source = source,
            newBranch = suggestLocalBranchName(source, localBranchNames),
            remoteSourceToFetch = source,
            requiredNewBranchReason = RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED,
        )
    }

    if (!canFastForwardExistingLocalBranch) {
        return WorktreeSourceResolution(
            source = source,
            newBranch = suggestLocalBranchName(source, localBranchNames),
            remoteSourceToFetch = source,
            requiredNewBranchReason = RequiredNewBranchReason.LOCAL_BRANCH_CANNOT_FAST_FORWARD,
        )
    }

    return WorktreeSourceResolution(
        source = implicitLocalBranch,
        remoteSourceToFetch = source,
        localBranchToFastForward = implicitLocalBranch,
    )
}

internal fun parseRemoteSource(source: String): Pair<String, String>? {
    val separator = source.indexOf('/')
    if (separator <= 0 || separator == source.lastIndex) {
        return null
    }
    return source.substring(0, separator) to source.substring(separator + 1)
}

internal fun remoteTrackingRef(source: String): String = "refs/remotes/$source"

internal fun localBranchRef(branchName: String): String = "refs/heads/$branchName"

internal fun collectOccupiedBranchNames(worktrees: List<WorktreeInfo>): Set<String> = worktrees.mapNotNull { it.branch }.toSet()

@Service(Service.Level.APP)
class GitWorktreeManager {

    companion object {
        private val LOG = logger<GitWorktreeManager>()
        fun getInstance(): GitWorktreeManager = service()
    }

    fun listWorktrees(project: Project, repository: GitRepository): List<WorktreeInfo> {
        val result = runGit(repository, "worktree", "list", "--porcelain")
        if (!result.success()) return emptyList()

        val lines = result.output.lines()
        val worktrees = mutableListOf<WorktreeInfo>()
        var currentPath: String? = null
        var currentHead = ""
        var currentBranch: String? = null
        var locked = false
        var lockReason: String? = null
        var prunable = false
        var bare = false

        fun flushEntry() {
            val path = currentPath ?: return
            worktrees.add(
                WorktreeInfo(
                    path = path,
                    head = currentHead,
                    branch = currentBranch,
                    isLocked = locked,
                    lockReason = lockReason,
                    isPrunable = prunable,
                    isBare = bare,
                    isMain = worktrees.isEmpty(),
                )
            )
        }

        for (line in lines) {
            when {
                line.isBlank() -> {
                    flushEntry()
                    currentPath = null
                    currentHead = ""
                    currentBranch = null
                    locked = false
                    lockReason = null
                    prunable = false
                    bare = false
                }
                line.startsWith("worktree ") -> currentPath = line.removePrefix("worktree ").trim()
                line.startsWith("HEAD ") -> currentHead = line.removePrefix("HEAD ").trim()
                line.startsWith("branch ") -> {
                    val ref = line.removePrefix("branch ").trim()
                    currentBranch = if (ref.startsWith("refs/heads/")) ref.removePrefix("refs/heads/") else ref
                }
                line == "locked" -> locked = true
                line.startsWith("locked ") -> { locked = true; lockReason = line.removePrefix("locked ").trim() }
                line == "prunable" -> prunable = true
                line == "bare" -> bare = true
                line == "detached" -> currentBranch = null
            }
        }
        flushEntry()
        return worktrees
    }

    fun addWorktree(
        project: Project,
        repository: GitRepository,
        path: String,
        source: String,
        newBranch: String? = null,
        lock: Boolean = false,
    ): GitWorktreeResult {
        val resolutionCheck = inspectRemoteBranchResolution(project, repository, source, newBranch)
        if (resolutionCheck.errorMessage != null) {
            return GitWorktreeResult(-1, "", resolutionCheck.errorMessage)
        }
        val resolution = resolutionCheck.resolution
            ?: return GitWorktreeResult(-1, "", "Unable to resolve worktree source")

        if (newBranch == null && resolution.requiredNewBranchReason != null) {
            return GitWorktreeResult(
                -1,
                "",
                buildRequiredNewBranchMessage(source, resolution)
            )
        }

        resolution.localBranchToFastForward?.let { branchName ->
            val fastForwardResult = fastForwardLocalBranchToRemote(repository, branchName, source)
            if (!fastForwardResult.success()) {
                return fastForwardResult
            }
            repository.update()
        }

        val params = mutableListOf("worktree", "add")
        resolution.newBranch?.let {
            params.add("-b")
            params.add(it)
        }
        if (lock) params.add("--lock")
        params.add(path)
        params.add(resolution.source)
        return runGit(repository, *params.toTypedArray())
    }

    internal fun inspectRemoteBranchResolution(
        project: Project,
        repository: GitRepository,
        source: String,
        requestedNewBranch: String? = null,
    ): RemoteBranchResolutionCheck {
        val remoteBranchNames = repository.branches.remoteBranches.map { it.name }.toSet()
        val initialRemoteSource = source.takeIf { it in remoteBranchNames }

        if (initialRemoteSource != null) {
            val fetchResult = fetchRemoteBranch(repository, initialRemoteSource)
            if (!fetchResult.success()) {
                return RemoteBranchResolutionCheck(errorMessage = fetchResult.errorOutputAsJoinedString.ifBlank {
                    fetchResult.output.trim().ifBlank { "Failed to fetch remote branch: $initialRemoteSource" }
                })
            }
            repository.update()
        }

        val refreshedLocalBranchNames = repository.branches.localBranches.map { it.name }.toSet()
        val refreshedRemoteBranchNames = repository.branches.remoteBranches.map { it.name }.toSet()
        val occupiedBranchNames = collectOccupiedBranchNames(listWorktrees(project, repository))
        val implicitLocalBranch = source.substringAfter("/", source)
        val canFastForwardExistingLocalBranch = if (
            initialRemoteSource != null &&
            requestedNewBranch == null &&
            implicitLocalBranch in refreshedLocalBranchNames &&
            implicitLocalBranch !in occupiedBranchNames
        ) {
            canFastForwardLocalBranch(repository, implicitLocalBranch, initialRemoteSource)
        } else {
            true
        }

        return RemoteBranchResolutionCheck(
            resolution = resolveWorktreeSource(
                source = source,
                requestedNewBranch = requestedNewBranch,
                localBranchNames = refreshedLocalBranchNames,
                remoteBranchNames = refreshedRemoteBranchNames,
                occupiedBranchNames = occupiedBranchNames,
                canFastForwardExistingLocalBranch = canFastForwardExistingLocalBranch,
            )
        )
    }

    fun removeWorktree(
        project: Project, repository: GitRepository, path: String, force: Boolean = false,
    ): GitWorktreeResult {
        val params = mutableListOf("worktree", "remove")
        if (force) params.add("--force")
        params.add(path)
        return runGit(repository, *params.toTypedArray())
    }

    fun lockWorktree(
        project: Project, repository: GitRepository, path: String, reason: String? = null,
    ): GitWorktreeResult {
        val params = mutableListOf("worktree", "lock")
        if (reason != null) { params.add("--reason"); params.add(reason) }
        params.add(path)
        return runGit(repository, *params.toTypedArray())
    }

    fun unlockWorktree(
        project: Project, repository: GitRepository, path: String,
    ): GitWorktreeResult = runGit(repository, "worktree", "unlock", path)

    fun pruneWorktrees(
        project: Project, repository: GitRepository,
    ): GitWorktreeResult = runGit(repository, "worktree", "prune")

    private fun fetchRemoteBranch(repository: GitRepository, source: String): GitWorktreeResult {
        val (remoteName, branchName) = parseRemoteSource(source) ?: return GitWorktreeResult(-1, "", "Invalid remote branch: $source")
        val refspec = "refs/heads/$branchName:${remoteTrackingRef(source)}"
        return runGit(repository, "fetch", remoteName, refspec)
    }

    private fun canFastForwardLocalBranch(repository: GitRepository, localBranchName: String, source: String): Boolean {
        val result = runGit(
            repository,
            "merge-base",
            "--is-ancestor",
            localBranchRef(localBranchName),
            remoteTrackingRef(source),
        )
        return result.exitCode == 0
    }

    private fun fastForwardLocalBranchToRemote(repository: GitRepository, localBranchName: String, source: String): GitWorktreeResult {
        return runGit(repository, "branch", "-f", localBranchName, remoteTrackingRef(source))
    }

    private fun buildRequiredNewBranchMessage(source: String, resolution: WorktreeSourceResolution): String {
        val localBranchName = source.substringAfter("/", source)
        val suggestedBranchName = resolution.newBranch ?: localBranchName
        return when (resolution.requiredNewBranchReason) {
            RequiredNewBranchReason.MISSING_LOCAL_BRANCH ->
                "Remote branch $source requires a new local branch. Suggested branch: $suggestedBranchName"
            RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED ->
                "Local branch $localBranchName is already used by another worktree. Suggested branch: $suggestedBranchName"
            RequiredNewBranchReason.LOCAL_BRANCH_CANNOT_FAST_FORWARD ->
                "Local branch $localBranchName cannot be fast-forwarded to $source safely. Suggested branch: $suggestedBranchName"
            null ->
                "Worktree creation requires a new branch. Suggested branch: $suggestedBranchName"
        }
    }

    private fun runGit(repository: GitRepository, vararg args: String): GitWorktreeResult {
        return try {
            val gitExe = try {
                GitExecutableManager.getInstance().getExecutable(repository.project).exePath
            } catch (_: Exception) {
                "git"
            }
            val command = mutableListOf(gitExe).apply { addAll(args) }
            val process = ProcessBuilder(command)
                .directory(File(repository.root.path))
                .start()

            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()
            val stdoutReader = thread(start = true, isDaemon = true, name = "git-worktree-stdout") {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        stdoutBuffer.appendLine(line)
                    }
                }
            }
            val stderrReader = thread(start = true, isDaemon = true, name = "git-worktree-stderr") {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        stderrBuffer.appendLine(line)
                    }
                }
            }

            val exited = process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                return GitWorktreeResult(-1, "", "Command timed out")
            }

            stdoutReader.join(2_000)
            stderrReader.join(2_000)
            GitWorktreeResult(process.exitValue(), stdoutBuffer.toString(), stderrBuffer.toString())
        } catch (e: Exception) {
            LOG.warn("Failed to run git command: ${args.toList()}", e)
            GitWorktreeResult(-1, "", e.message ?: "Unknown error")
        }
    }
}
