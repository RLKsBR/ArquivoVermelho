package com.rlks.voicecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemRecipeBookTest {
    @Test
    fun findsRecipesWithPortugueseAndEnglishAliases() {
        assertEquals("Gume do Infinito", ItemRecipeBook.find("gume")?.name)
        assertEquals("Gume do Infinito", ItemRecipeBook.find("Infinity Edge")?.name)
        assertEquals("Lança de Shojin", ItemRecipeBook.find("lança de shojin")?.name)
    }

    @Test
    fun listsEveryStandardCombination() {
        assertEquals(36, ItemRecipeBook.recipes.size)
        assertEquals(8, ItemRecipeBook.components.size)
    }

    @Test
    fun findsItemsMadeFromAComponent() {
        val names = ItemRecipeBook.recipesUsing("arco").map { it.name }
        assertEquals(8, names.size)
        assertTrue("Buff Vermelho" in names)
        assertTrue("Último Sussurro" in names)
    }
}
