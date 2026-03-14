package com.github.gitworktree.dialog

import com.github.gitworktree.GitWorktreeBundle
import com.github.gitworktree.git.GitWorktreeManager
import com.github.gitworktree.git.RemoteBranchResolutionCheck
import com.github.gitworktree.git.RequiredNewBranchReason
import com.github.gitworktree.git.suggestLocalBranchName
import com.github.gitworktree.settings.GitWorktreeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

enum class WorktreeSourceType(
    val value: String,
    private val bundleKey: String,
) {
    HEAD("HEAD", "dialog.add.source.head"),
    BRANCH("BRANCH", "dialog.add.source.branch"),
    COMMIT("COMMIT", "dialog.add.source.commit"),
    TAG("TAG", "dialog.add.source.tag");

    override fun toString(): String {
        return GitWorktreeBundle.message(bundleKey)
    }

    companion object {
        fun fromValue(value: String?): WorktreeSourceType? {
            return when (value?.uppercase()) {
                HEAD.value -> HEAD
                BRANCH.value -> BRANCH
                COMMIT.value -> COMMIT
                TAG.value -> TAG
                else -> when (value) {
                    "Branch" -> BRANCH
                    "Commit" -> COMMIT
                    "Tag" -> TAG
                    "HEAD" -> HEAD
                    else -> null
                }
            }
        }
    }
}

data class AddWorktreeResult(
    val location: String,
    val source: String,
    val newBranch: String?,
    val lock: Boolean,
    val copyIdea: Boolean,
    val copyWorktreeFiles: Boolean,
    val runExternalTool: Boolean,
    val externalToolName: String?,
    val openAfterCreation: Boolean,
)

class AsyncComboBoxLoader<T>(
    private val comboBox: ComboBox<T>,
    private val submitter: ((() -> List<T>), (List<T>) -> Unit) -> Unit,
) {

    fun load(itemsSupplier: () -> List<T>) {
        comboBox.removeAllItems()
        comboBox.isEnabled = false
        submitter(itemsSupplier, ::updateItems)
    }

    private fun updateItems(items: List<T>) {
        comboBox.removeAllItems()
        items.forEach(comboBox::addItem)
        comboBox.isEnabled = items.isNotEmpty()
    }
}

internal fun toPathSafeBranchName(branchName: String): String {
    return branchName
        .trim()
        .replace("/", "-")
        .ifBlank { "detached" }
}

internal fun resolveSuggestedLocation(repoRoot: String, renderedPath: String): String {
    return File(repoRoot)
        .toPath()
        .resolve(renderedPath)
        .normalize()
        .toString()
}

internal fun resolveSuggestedPathBranchName(
    sourceType: WorktreeSourceType,
    selectedBranch: String?,
    createNewBranch: Boolean,
    newBranchName: String?,
    remoteBranchNames: Set<String>,
    existingLocalBranchNames: Set<String>,
    fallbackBranchName: String,
): String {
    if (sourceType == WorktreeSourceType.BRANCH) {
        selectedBranch?.takeIf(String::isNotBlank)?.let { branchName ->
            val sourceLeafName = branchName.substringAfter("/", branchName)
            if (branchName in remoteBranchNames) {
                if (createNewBranch) {
                    val trimmedNewBranchName = newBranchName?.trim()
                    val suggestedLocalBranchName = suggestLocalBranchName(branchName, existingLocalBranchNames)
                    if (!trimmedNewBranchName.isNullOrEmpty() && trimmedNewBranchName != suggestedLocalBranchName) {
                        return toPathSafeBranchName(trimmedNewBranchName)
                    }
                }
                return toPathSafeBranchName(sourceLeafName)
            }
            return toPathSafeBranchName(branchName)
        }
    }

    if (createNewBranch) {
        newBranchName?.trim()?.takeIf(String::isNotEmpty)?.let { return toPathSafeBranchName(it) }
    }

    return toPathSafeBranchName(fallbackBranchName)
}

class AddWorktreeDialog(
    private val project: Project,
    private val repository: GitRepository,
    private val presetCommit: String? = null,
    private val presetSource: String? = null,
    private val presetBranchName: String? = null,
) : DialogWrapper(project) {

    companion object {
        private const val CARD_EMPTY = "EMPTY"
        private const val CARD_BRANCH = "BRANCH"
        private const val CARD_COMMIT = "COMMIT"
        private const val CARD_TAG = "TAG"
    }

    private val settingsState = GitWorktreeSettings.getInstance(project).state
    private val localBranchNames: Set<String>
        get() = repository.branches.localBranches.map { it.name }.toSet()
    private val remoteBranchNames: Set<String>
        get() = repository.branches.remoteBranches.map { it.name }.toSet()
    private var locationSuggestionReady = false
    private var lastSuggestedLocation: String? = null
    private var lastSuggestedNewBranchName: String? = null
    private var remoteBranchCheckRequestId = 0
    private var remoteBranchResolutionCheck = RemoteBranchResolutionCheck()
    private var remoteBranchCheckInProgress = false

    private val locationField = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = GitWorktreeBundle.message("dialog.add.location.title")
            description = GitWorktreeBundle.message("dialog.add.location.description")
        }
        addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
        text = suggestDefaultLocation()
    }

    private val sourceCombo = ComboBox(WorktreeSourceType.entries.toTypedArray())

    private val branchCombo = ComboBox<String>().apply {
        isEditable = false
        populateBranches()
    }

    private val commitField = JBTextField().apply {
        emptyText.text = GitWorktreeBundle.message("dialog.add.commit.placeholder")
    }

    private val tagCombo = ComboBox<String>().apply {
        isEditable = false
        isEnabled = false
    }

    private val tagComboLoader = AsyncComboBoxLoader(tagCombo, ::submitTagLoading)
    private var tagsRequested = false

    private val sourceValueCardLayout = CardLayout()
    private val sourceValuePanel = JPanel(sourceValueCardLayout).apply {
        add(JPanel(), CARD_EMPTY)
        add(branchCombo, CARD_BRANCH)
        add(commitField, CARD_COMMIT)
        add(tagCombo, CARD_TAG)
    }

    private val createNewBranchCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.branch.toggle"))
    private val newBranchNameField = JBTextField().apply {
        emptyText.text = GitWorktreeBundle.message("dialog.add.branch.placeholder")
        isEnabled = false
    }
    private val remoteBranchHintLabel = JBLabel().apply {
        isVisible = false
    }

    // Advanced
    private val lockCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.lock"))

    // After creation
    private val copyIdeaCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.copy.idea")).apply {
        isSelected = settingsState.copyIdeaByDefault
    }
    private val copyWorktreeFilesCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.copy.worktree.files")).apply {
        isSelected = settingsState.copyWorktreeFilesByDefault
    }
    private val runExternalToolCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.run.external.tool")).apply {
        isSelected = settingsState.runExternalToolByDefault
    }
    private val externalToolField = JBTextField().apply {
        emptyText.text = GitWorktreeBundle.message("dialog.add.external.tool.placeholder")
        text = settingsState.defaultExternalTool
        isEnabled = settingsState.runExternalToolByDefault
    }
    private val openAfterCreationCheckBox = JBCheckBox(GitWorktreeBundle.message("dialog.add.open.after")).apply {
        isSelected = settingsState.openAfterCreationByDefault
    }

    init {
        title = GitWorktreeBundle.message("dialog.add.title")
        locationSuggestionReady = true
        setupListeners()
        applyPresets()
        init()
        refreshSuggestedLocation(force = true)
        refreshRemoteBranchRequirement()
    }

    private fun suggestDefaultLocation(): String {
        val repoRoot = repository.root.path
        val repoDir = File(repoRoot)
        val repoName = repoDir.name
        val pathTemplate = settingsState.defaultPathTemplate
            .takeIf { it.isNotBlank() }
            ?: GitWorktreeSettings.DEFAULT_PATH_TEMPLATE
        val fallbackBranchName = defaultBranchName()
        val renderedBranchName = if (locationSuggestionReady) {
            resolveSuggestedPathBranchName(
                sourceType = sourceCombo.selectedItem as? WorktreeSourceType ?: WorktreeSourceType.HEAD,
                selectedBranch = branchCombo.selectedItem as? String,
                createNewBranch = createNewBranchCheckBox.isSelected,
                newBranchName = newBranchNameField.text,
                remoteBranchNames = remoteBranchNames,
                existingLocalBranchNames = localBranchNames,
                fallbackBranchName = fallbackBranchName,
            )
        } else {
            fallbackBranchName
        }
        val renderedPath = pathTemplate
            .replace("{repo}", repoName)
            .replace("{branch}", renderedBranchName)

        return resolveSuggestedLocation(repoRoot, renderedPath)
    }

    private fun defaultBranchName(): String {
        return when {
            !presetBranchName.isNullOrBlank() -> presetBranchName
            !repository.currentBranchName.isNullOrBlank() -> repository.currentBranchName
            !presetCommit.isNullOrBlank() -> presetCommit.take(8)
            else -> "detached"
        }.orEmpty()
    }

    private fun ComboBox<String>.populateBranches() {
        val localBranches = repository.branches.localBranches.map { it.name }.sorted()
        val remoteBranches = repository.branches.remoteBranches.map { it.name }.sorted()
        localBranches.forEach { addItem(it) }
        remoteBranches.forEach { addItem(it) }
    }

    private fun loadTagsIfNeeded() {
        if (tagsRequested) {
            return
        }
        tagsRequested = true
        tagComboLoader.load(::fetchTags)
    }

    private fun fetchTags(): List<String> {
        return try {
            val gitExecutable = try {
                GitExecutableManager.getInstance().getExecutable(project).exePath
            } catch (_: Exception) {
                "git"
            }

            val process = ProcessBuilder(gitExecutable, "tag", "-l")
                .directory(File(repository.root.path))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines().filter { it.isNotBlank() }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun submitTagLoading(itemsSupplier: () -> List<String>, onLoaded: (List<String>) -> Unit) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, GitWorktreeBundle.message("dialog.add.loading.tags"), false) {
                override fun run(indicator: ProgressIndicator) {
                    val tags = itemsSupplier()
                    ApplicationManager.getApplication().invokeLater {
                        if (!isDisposed) {
                            onLoaded(tags)
                        }
                    }
                }
            }
        )
    }

    private fun setupListeners() {
        sourceCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                when (e.item as WorktreeSourceType) {
                    WorktreeSourceType.HEAD -> sourceValueCardLayout.show(sourceValuePanel, CARD_EMPTY)
                    WorktreeSourceType.BRANCH -> sourceValueCardLayout.show(sourceValuePanel, CARD_BRANCH)
                    WorktreeSourceType.COMMIT -> sourceValueCardLayout.show(sourceValuePanel, CARD_COMMIT)
                    WorktreeSourceType.TAG -> {
                        sourceValueCardLayout.show(sourceValuePanel, CARD_TAG)
                        loadTagsIfNeeded()
                    }
                }
                maybeSuggestNewBranchFromRemote(force = false)
                refreshRemoteBranchRequirement()
                refreshSuggestedLocation()
            }
        }

        branchCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                maybeSuggestNewBranchFromRemote(force = true)
                refreshRemoteBranchRequirement()
                refreshSuggestedLocation()
            }
        }

        createNewBranchCheckBox.addItemListener {
            newBranchNameField.isEnabled = createNewBranchCheckBox.isSelected
            if (createNewBranchCheckBox.isSelected) {
                maybeSuggestNewBranchFromRemote(force = false)
            }
            updateRemoteBranchHint(remoteBranchResolutionCheck)
            refreshSuggestedLocation()
        }

        runExternalToolCheckBox.addItemListener {
            externalToolField.isEnabled = runExternalToolCheckBox.isSelected
        }

        newBranchNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshSuggestedLocation()
            override fun removeUpdate(e: DocumentEvent) = refreshSuggestedLocation()
            override fun changedUpdate(e: DocumentEvent) = refreshSuggestedLocation()
        })
    }

    private fun applyPresets() {
        WorktreeSourceType.fromValue(presetSource)?.let {
            sourceCombo.selectedItem = it
        }
        if (presetCommit != null) {
            commitField.text = presetCommit
            if (presetSource == null) {
                sourceCombo.selectedItem = WorktreeSourceType.COMMIT
            }
        }
        if (presetBranchName != null) {
            branchCombo.selectedItem = presetBranchName
            if (presetSource == null) {
                sourceCombo.selectedItem = WorktreeSourceType.BRANCH
            }
        }
    }

    private fun maybeSuggestNewBranchFromRemote(force: Boolean) {
        val sourceType = sourceCombo.selectedItem as? WorktreeSourceType ?: return
        if (sourceType != WorktreeSourceType.BRANCH) {
            return
        }

        val selectedBranch = branchCombo.selectedItem as? String ?: return
        if (selectedBranch !in remoteBranchNames) {
            return
        }

        val suggestedBranchName = suggestLocalBranchName(selectedBranch, localBranchNames)
        val currentBranchName = newBranchNameField.text.trim()
        if (createNewBranchCheckBox.isSelected && (force || currentBranchName.isEmpty() || currentBranchName == lastSuggestedNewBranchName)) {
            newBranchNameField.text = suggestedBranchName
        }
        lastSuggestedNewBranchName = suggestedBranchName
    }

    private fun refreshRemoteBranchRequirement() {
        val sourceType = sourceCombo.selectedItem as? WorktreeSourceType
        val selectedBranch = branchCombo.selectedItem as? String
        val branchKnownToRepository = selectedBranch != null &&
            (selectedBranch in remoteBranchNames || selectedBranch in localBranchNames)
        if (sourceType != WorktreeSourceType.BRANCH || selectedBranch.isNullOrBlank() || !branchKnownToRepository) {
            remoteBranchCheckRequestId += 1
            remoteBranchResolutionCheck = RemoteBranchResolutionCheck()
            remoteBranchCheckInProgress = false
            updateRemoteBranchHint(remoteBranchResolutionCheck)
            return
        }

        val requestId = ++remoteBranchCheckRequestId
        remoteBranchCheckInProgress = true
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, GitWorktreeBundle.message("dialog.add.checking.remote.branch"), false) {
                override fun run(indicator: ProgressIndicator) {
                    val resolutionCheck = GitWorktreeManager.getInstance()
                        .inspectRemoteBranchResolution(project, repository, selectedBranch)
                    ApplicationManager.getApplication().invokeLater {
                        if (!isDisposed && requestId == remoteBranchCheckRequestId) {
                            remoteBranchCheckInProgress = false
                            remoteBranchResolutionCheck = resolutionCheck
                            applyRemoteBranchResolutionCheck(resolutionCheck)
                        }
                    }
                }
            }
        )
    }

    private fun applyRemoteBranchResolutionCheck(resolutionCheck: RemoteBranchResolutionCheck) {
        val suggestedBranchName = resolutionCheck.resolution?.newBranch
        if (resolutionCheck.resolution?.requiredNewBranchReason != null && !suggestedBranchName.isNullOrBlank()) {
            if (!createNewBranchCheckBox.isSelected) {
                createNewBranchCheckBox.isSelected = true
            }
            val currentBranchName = newBranchNameField.text.trim()
            if (currentBranchName.isEmpty() || currentBranchName == lastSuggestedNewBranchName) {
                newBranchNameField.text = suggestedBranchName
            }
            lastSuggestedNewBranchName = suggestedBranchName
        }
        updateRemoteBranchHint(resolutionCheck)
        refreshSuggestedLocation()
    }

    private fun updateRemoteBranchHint(resolutionCheck: RemoteBranchResolutionCheck) {
        val resolution = resolutionCheck.resolution
        val selectedBranch = branchCombo.selectedItem as? String
        val hintText = when {
            !resolutionCheck.errorMessage.isNullOrBlank() ->
                GitWorktreeBundle.message("dialog.add.remote.branch.check.failed", resolutionCheck.errorMessage)
            resolution?.requiredNewBranchReason == RequiredNewBranchReason.MISSING_LOCAL_BRANCH ->
                GitWorktreeBundle.message(
                    "dialog.add.remote.branch.missing.local",
                    selectedBranch.orEmpty(),
                    resolution.newBranch.orEmpty(),
                )
            resolution?.requiredNewBranchReason == RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED ->
                GitWorktreeBundle.message(
                    "dialog.add.remote.branch.occupied",
                    resolution.source.substringAfter("/", resolution.source),
                    resolution.newBranch.orEmpty(),
                )
            resolution?.requiredNewBranchReason == RequiredNewBranchReason.LOCAL_BRANCH_CANNOT_FAST_FORWARD ->
                GitWorktreeBundle.message(
                    "dialog.add.remote.branch.cannot.fast.forward",
                    selectedBranch?.substringAfter("/", selectedBranch).orEmpty(),
                    selectedBranch.orEmpty(),
                    resolution.newBranch.orEmpty(),
                )
            else -> null
        }
        remoteBranchHintLabel.text = hintText.orEmpty()
        remoteBranchHintLabel.isVisible = !hintText.isNullOrBlank()
    }

    private fun refreshSuggestedLocation(force: Boolean = false) {
        val suggestedLocation = suggestDefaultLocation()
        val currentLocation = locationField.text.trim()
        if (force || currentLocation.isEmpty() || currentLocation == lastSuggestedLocation) {
            locationField.text = suggestedLocation
        }
        lastSuggestedLocation = suggestedLocation
    }

    override fun createCenterPanel(): JComponent {
        val newBranchPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(createNewBranchCheckBox, BorderLayout.WEST)
            add(newBranchNameField, BorderLayout.CENTER)
        }

        val externalToolPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(runExternalToolCheckBox, BorderLayout.WEST)
            add(externalToolField, BorderLayout.CENTER)
        }

        val advancedContent = FormBuilder.createFormBuilder()
            .addComponent(lockCheckBox)
            .panel

        val advancedSection = JPanel(BorderLayout()).apply {
            add(TitledSeparator(GitWorktreeBundle.message("dialog.add.advanced")), BorderLayout.NORTH)
            add(advancedContent, BorderLayout.CENTER)
        }

        val afterCreationContent = FormBuilder.createFormBuilder()
            .addComponent(copyIdeaCheckBox)
            .addComponent(copyWorktreeFilesCheckBox)
            .addComponent(externalToolPanel)
            .addComponent(openAfterCreationCheckBox)
            .panel

        val afterCreationSection = JPanel(BorderLayout()).apply {
            add(TitledSeparator(GitWorktreeBundle.message("dialog.add.post.create")), BorderLayout.NORTH)
            add(afterCreationContent, BorderLayout.CENTER)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(GitWorktreeBundle.message("dialog.add.location")), locationField)
            .addLabeledComponent(JBLabel(GitWorktreeBundle.message("dialog.add.source")), sourceCombo)
            .addLabeledComponent(JBLabel(GitWorktreeBundle.message("dialog.add.source.value")), sourceValuePanel)
            .addComponent(newBranchPanel)
            .addComponent(remoteBranchHintLabel)
            .addComponent(advancedSection)
            .addComponent(afterCreationSection)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { preferredSize = JBUI.size(500, 400) }
    }

    override fun doValidate(): ValidationInfo? {
        val location = locationField.text.trim()
        if (location.isEmpty()) {
            return ValidationInfo(GitWorktreeBundle.message("dialog.add.validation.location.empty"), locationField)
        }
        val locationDir = File(location)
        if (locationDir.exists() && locationDir.isDirectory && locationDir.list()?.isNotEmpty() == true) {
            return ValidationInfo(GitWorktreeBundle.message("dialog.add.validation.location.not.empty"), locationField)
        }

        when (sourceCombo.selectedItem as? WorktreeSourceType ?: WorktreeSourceType.HEAD) {
            WorktreeSourceType.BRANCH -> {
                if (branchCombo.selectedItem == null) {
                    return ValidationInfo(GitWorktreeBundle.message("dialog.add.validation.branch.required"), branchCombo)
                }
                if (remoteBranchCheckInProgress) {
                    return ValidationInfo(
                        GitWorktreeBundle.message("dialog.add.validation.branch.checking"),
                        branchCombo,
                    )
                }
                if (remoteBranchResolutionCheck.resolution?.requiredNewBranchReason != null && !createNewBranchCheckBox.isSelected) {
                    return ValidationInfo(
                        GitWorktreeBundle.message("dialog.add.validation.remote.branch.requires.new.branch"),
                        createNewBranchCheckBox,
                    )
                }
            }
            WorktreeSourceType.COMMIT -> {
                if (commitField.text.trim().isEmpty()) {
                    return ValidationInfo(GitWorktreeBundle.message("dialog.add.validation.commit.required"), commitField)
                }
            }
            WorktreeSourceType.TAG -> {
                if (tagCombo.selectedItem == null) {
                    return ValidationInfo(GitWorktreeBundle.message("dialog.add.validation.tag.required"), tagCombo)
                }
            }
            WorktreeSourceType.HEAD -> Unit
        }

        if (createNewBranchCheckBox.isSelected) {
            val branchName = newBranchNameField.text.trim()
            if (branchName.isEmpty()) {
                return ValidationInfo(
                    GitWorktreeBundle.message("dialog.add.validation.new.branch.required"),
                    newBranchNameField,
                )
            }
            if (!isValidBranchName(branchName)) {
                return ValidationInfo(
                    GitWorktreeBundle.message("dialog.add.validation.new.branch.invalid", branchName),
                    newBranchNameField,
                )
            }
        }

        return null
    }

    private fun isValidBranchName(name: String): Boolean {
        if (name.startsWith("-") || name.startsWith(".")) return false
        if (name.endsWith(".") || name.endsWith(".lock")) return false
        if (name.contains("..") || name.contains("~") || name.contains("^") ||
            name.contains(":") || name.contains("\\") || name.contains(" ") ||
            name.contains("?") || name.contains("*") || name.contains("[")
        ) return false
        return name.isNotEmpty()
    }

    fun getResult(): AddWorktreeResult {
        val sourceType = sourceCombo.selectedItem as? WorktreeSourceType ?: WorktreeSourceType.HEAD
        val sourceValue = when (sourceType) {
            WorktreeSourceType.HEAD -> WorktreeSourceType.HEAD.value
            WorktreeSourceType.BRANCH -> branchCombo.selectedItem as? String ?: ""
            WorktreeSourceType.COMMIT -> commitField.text.trim()
            WorktreeSourceType.TAG -> tagCombo.selectedItem as? String ?: ""
        }

        return AddWorktreeResult(
            location = locationField.text.trim(),
            source = sourceValue,
            newBranch = if (createNewBranchCheckBox.isSelected) newBranchNameField.text.trim() else null,
            lock = lockCheckBox.isSelected,
            copyIdea = copyIdeaCheckBox.isSelected,
            copyWorktreeFiles = copyWorktreeFilesCheckBox.isSelected,
            runExternalTool = runExternalToolCheckBox.isSelected,
            externalToolName = if (runExternalToolCheckBox.isSelected) externalToolField.text.trim() else null,
            openAfterCreation = openAfterCreationCheckBox.isSelected,
        )
    }
}
