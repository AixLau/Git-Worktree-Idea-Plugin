package com.github.gitworktree.toolwindow

import com.github.gitworktree.GitWorktreeBundle
import com.github.gitworktree.GitWorktreeDataKeys
import com.github.gitworktree.git.GitWorktreeManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class WorktreePanel(private val project: Project) : JPanel(BorderLayout()), DataProvider {

    private data class RepoViewState(
        val repoName: String,
        val repoRoot: String,
        val worktrees: List<WorktreeNode>,
    )

    companion object {
        private const val TOOL_WINDOW_ID = "Git Worktree"

        fun refreshAll(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }

                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
                toolWindow.contentManager.contents
                    .mapNotNull { it.component as? WorktreePanel }
                    .forEach(WorktreePanel::refresh)
            }
        }
    }

    private val rootNode = DefaultMutableTreeNode(GitWorktreeBundle.message("panel.title"))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    @Volatile
    private var refreshRequestId = 0

    init {
        tree.isRootVisible = false
        tree.cellRenderer = WorktreeTreeCellRenderer()

        setupToolbar()
        setupContextMenu()
        setupDoubleClick()

        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        project.messageBus.connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { refresh() }
        )
        StartupManager.getInstance(project).runAfterOpened { refresh() }
        refresh()
    }
    private fun setupToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            addActionIfExists("GitWorktree.Add")
            addSeparator()
            add(object : AnAction(
                GitWorktreeBundle.message("action.refresh"),
                GitWorktreeBundle.message("action.refresh.description"),
                null,
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    refresh()
                }
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("GitWorktreeToolbar", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
    }

    private fun DefaultActionGroup.addActionIfExists(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId)
        if (action != null) {
            add(action)
        }
    }

    private fun setupContextMenu() {
        val popupGroup = DefaultActionGroup().apply {
            addActionIfExists("GitWorktree.Add")
            addActionIfExists("GitWorktree.Remove")
            addSeparator()
            addActionIfExists("GitWorktree.Lock")
            addActionIfExists("GitWorktree.Unlock")
            addSeparator()
            addActionIfExists("GitWorktree.QuickSwitch")
        }
        PopupHandler.installPopupMenu(tree, popupGroup, "GitWorktreePopup")
    }
    private fun setupDoubleClick() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val wtNode = node.userObject as? WorktreeNode ?: return
                    quickSwitch(wtNode.info.path)
                }
            }
        })
    }

    private fun quickSwitch(worktreePath: String) {
        val dir = File(worktreePath)
        if (!dir.isDirectory) return
        ProjectManager.getInstance().loadAndOpenProject(worktreePath)
    }

    fun refresh() {
        val requestId = ++refreshRequestId
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, GitWorktreeBundle.message("action.refresh.description"), false) {
                override fun run(indicator: ProgressIndicator) {
                    val repoStates = loadRepoStates()
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || requestId != refreshRequestId) {
                            return@invokeLater
                        }
                        applyRepoStates(repoStates)
                    }
                }
            }
        )
    }

    private fun loadRepoStates(): List<RepoViewState> {
        val repoManager = GitRepositoryManager.getInstance(project)
        val manager = GitWorktreeManager.getInstance()

        return repoManager.repositories.map { repo ->
            val repoRoot = repo.root.path
            val worktreeNodes = manager.listWorktrees(project, repo)
                .map { WorktreeNode(it, repoRoot) }

            RepoViewState(
                repoName = repo.root.name,
                repoRoot = repoRoot,
                worktrees = worktreeNodes,
            )
        }
    }

    private fun applyRepoStates(repoStates: List<RepoViewState>) {
        rootNode.removeAllChildren()

        for (repoState in repoStates) {
            val repoNode = DefaultMutableTreeNode(RepoNode(repoState.repoName, repoState.repoRoot))
            repoState.worktrees.forEach { worktreeNode ->
                repoNode.add(DefaultMutableTreeNode(worktreeNode))
            }
            rootNode.add(repoNode)
        }

        treeModel.reload()
        expandAllNodes()
    }

    private fun expandAllNodes() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    fun getSelectedWorktreeNode(): WorktreeNode? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? WorktreeNode
    }

    override fun getData(dataId: String): Any? {
        if (GitWorktreeDataKeys.SELECTED_WORKTREE.`is`(dataId)) {
            return getSelectedWorktreeNode()?.info
        }
        if (GitWorktreeDataKeys.SELECTED_REPOSITORY.`is`(dataId)) {
            val wtNode = getSelectedWorktreeNode() ?: return null
            return GitRepositoryManager.getInstance(project).repositories
                .firstOrNull { it.root.path == wtNode.repoPath }
        }
        return null
    }
}
