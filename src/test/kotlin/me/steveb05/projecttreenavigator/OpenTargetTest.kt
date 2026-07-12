package me.steveb05.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenTargetTest : BasePlatformTestCase() {

    private lateinit var entries: List<BaseEntry>

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("engine/src/Region.kt", "")
        myFixture.addFileToProject("extensions/src/Vault.kt", "")
        myFixture.addFileToProject("docs/readme.md", "")
        entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("engine"), "engine", true),
            BaseEntry(myFixture.findFileInTempDir("extensions"), "extensions", true),
            BaseEntry(myFixture.findFileInTempDir("docs"), "docs", true),
        )
    }

    fun testTheFileBeingEditedWins() {
        val current = myFixture.findFileInTempDir("extensions/src/Vault.kt")
        val target = OpenTarget.choose(entries, current, remembered = entries[0].file, previous = entries[2])
        assertEquals(entries[1], target)
    }

    fun testWithoutTheFileInViewItFallsBackToTheRememberedEntry() {
        val outside = myFixture.addFileToProject("elsewhere/Other.kt", "").virtualFile
        val target = OpenTarget.choose(entries, outside, remembered = entries[0].file, previous = entries[2])
        assertEquals(entries[0], target)
    }

    fun testWithoutAnythingRememberedItKeepsWhatWasSelected() {
        val target = OpenTarget.choose(entries, current = null, remembered = null, previous = entries[2])
        assertEquals(entries[2], target)
    }

    fun testAsALastResortItTakesTheTopEntry() {
        val target = OpenTarget.choose(entries, current = null, remembered = null, previous = null)
        assertEquals(entries[0], target)
    }

    fun testAnEntryThatIsGoneIsNotChosen() {
        val stale = myFixture.addFileToProject("gone/x.kt", "").virtualFile.parent
        val target = OpenTarget.choose(entries, current = null, remembered = stale, previous = null)
        assertEquals("a remembered folder outside the view must not be selected", entries[0], target)
    }

    fun testNothingToChooseFrom() {
        assertNull(OpenTarget.choose(emptyList(), current = null, remembered = null, previous = null))
    }

    fun testTheTreeWalksOpenToTheFirstCandidateThatLivesUnderTheEntry() {
        val engine = entries[0].file
        val outside = myFixture.findFileInTempDir("extensions/src/Vault.kt")
        val inside = myFixture.findFileInTempDir("engine/src/Region.kt")
        assertEquals(inside, OpenTarget.landing(engine, outside, inside))
        assertNull("nothing under this entry to open to", OpenTarget.landing(engine, outside, null))
    }

    fun testTheEntryItselfCountsAsALanding() {
        val docs = entries[2].file
        assertEquals(docs, OpenTarget.landing(docs, docs))
    }
}
