package com.github.gitworktree.dialog

import com.github.gitworktree.git.RequiredNewBranchReason
import com.github.gitworktree.git.resolveWorktreeSource
import com.github.gitworktree.git.suggestLocalBranchName
import kotlin.test.Test
import kotlin.test.assertEquals

class AddWorktreeDialogBranchSuggestionTest {

    @Test
    fun `suggest local branch name keeps remote leaf when local branch does not exist`() {
        assertEquals("feature/demo", suggestLocalBranchName("origin/feature/demo", emptySet()))
    }

    @Test
    fun `suggest local branch name adds worktree suffix when local branch already exists`() {
        assertEquals("main-worktree", suggestLocalBranchName("origin/main", setOf("main")))
    }

    @Test
    fun `resolve suggested path branch name prefers explicit new branch`() {
        assertEquals(
            "idea",
            resolveSuggestedPathBranchName(
                sourceType = WorktreeSourceType.BRANCH,
                selectedBranch = "origin/main",
                createNewBranch = true,
                newBranchName = "idea",
                remoteBranchNames = setOf("origin/main"),
                existingLocalBranchNames = setOf("main"),
                fallbackBranchName = "main",
            )
        )
    }

    @Test
    fun `resolve suggested path branch name avoids remote branch path segment`() {
        assertEquals(
            "main",
            resolveSuggestedPathBranchName(
                sourceType = WorktreeSourceType.BRANCH,
                selectedBranch = "origin/main",
                createNewBranch = false,
                newBranchName = null,
                remoteBranchNames = setOf("origin/main"),
                existingLocalBranchNames = setOf("main"),
                fallbackBranchName = "main",
            )
        )
    }

    @Test
    fun `resolve suggested path branch name ignores auto appended worktree suffix for remote source`() {
        assertEquals(
            "main",
            resolveSuggestedPathBranchName(
                sourceType = WorktreeSourceType.BRANCH,
                selectedBranch = "origin/main",
                createNewBranch = true,
                newBranchName = "main-worktree",
                remoteBranchNames = setOf("origin/main"),
                existingLocalBranchNames = setOf("main"),
                fallbackBranchName = "main",
            )
        )
    }

    @Test
    fun `resolve suggested path branch name sanitizes slash for single sibling directory`() {
        assertEquals(
            "feature-demo",
            resolveSuggestedPathBranchName(
                sourceType = WorktreeSourceType.BRANCH,
                selectedBranch = "feature/demo",
                createNewBranch = false,
                newBranchName = null,
                remoteBranchNames = emptySet(),
                existingLocalBranchNames = setOf("feature/demo"),
                fallbackBranchName = "main",
            )
        )
    }

    @Test
    fun `resolve suggested location normalizes sibling path`() {
        assertEquals(
            "/Users/lushiwu/Documents/Java/agent-flow-main-worktree",
            resolveSuggestedLocation(
                "/Users/lushiwu/Documents/Java/agent-flow",
                "../agent-flow-main-worktree",
            )
        )
    }

    @Test
    fun `resolveWorktreeSource marks missing local branch as requiring a new branch`() {
        assertEquals(
            RequiredNewBranchReason.MISSING_LOCAL_BRANCH,
            resolveWorktreeSource(
                source = "origin/main",
                requestedNewBranch = null,
                localBranchNames = emptySet(),
                remoteBranchNames = setOf("origin/main"),
            ).requiredNewBranchReason
        )
    }

    @Test
    fun `resolveWorktreeSource marks occupied local branch as requiring a new branch`() {
        assertEquals(
            RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED,
            resolveWorktreeSource(
                source = "origin/main",
                requestedNewBranch = null,
                localBranchNames = setOf("main"),
                remoteBranchNames = setOf("origin/main"),
                occupiedBranchNames = setOf("main"),
            ).requiredNewBranchReason
        )
    }

    @Test
    fun `resolveWorktreeSource marks occupied direct local branch as requiring a new branch`() {
        assertEquals(
            RequiredNewBranchReason.LOCAL_BRANCH_OCCUPIED,
            resolveWorktreeSource(
                source = "main",
                requestedNewBranch = null,
                localBranchNames = setOf("main"),
                remoteBranchNames = emptySet(),
                occupiedBranchNames = setOf("main"),
            ).requiredNewBranchReason
        )
    }
}
