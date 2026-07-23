package me.steveb05.ideavifm

import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Delete is enabled only when the action finds a delete provider in the context. These pin the contract the
 * popup's data context has to satisfy for the platform to enable it.
 */
class DeleteProviderTest : BasePlatformTestCase() {

    fun testTheProviderEnablesDeleteForTheElementsThePopupPutsInTheContext() {
        val file = myFixture.addFileToProject("root/Doomed.kt", "").virtualFile
        val element = myFixture.psiManager.findFile(file) as PsiElement
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, arrayOf(element))
            .build()
        assertTrue(DeleteHandler.DefaultDeleteProvider().canDeleteElement(context))
    }

    fun testWithoutElementsThereIsNothingToDelete() {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, emptyArray<PsiElement>())
            .build()
        assertFalse(DeleteHandler.DefaultDeleteProvider().canDeleteElement(context))
    }
}
