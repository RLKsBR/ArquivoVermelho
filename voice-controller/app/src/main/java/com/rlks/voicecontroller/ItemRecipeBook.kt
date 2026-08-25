package com.rlks.voicecontroller

import java.text.Normalizer

data class ItemRecipe(
    val name: String,
    val firstComponent: String,
    val secondComponent: String,
    val aliases: Set<String> = emptySet()
)

object ItemRecipeBook {
    const val SWORD = "Espada G.p.C."
    const val BOW = "Arco Recurvo"
    const val ROD = "Bastão Desnecessariamente Grande"
    const val TEAR = "Lágrima da Deusa"
    const val VEST = "Cota de Malha"
    const val CLOAK = "Capa Negatron"
    const val BELT = "Cinto do Gigante"
    const val GLOVE = "Luvas do Pugilista"

    val components = listOf(SWORD, BOW, ROD, TEAR, VEST, CLOAK, BELT, GLOVE)

    val recipes = listOf(
        recipe("Lâmina da Morte", SWORD, SWORD, "deathblade"),
        recipe("Matador de Gigantes", SWORD, BOW, "giant slayer"),
        recipe("Pistola Laminar Hextec", SWORD, ROD, "gunblade", "hextech gunblade"),
        recipe("Lança de Shojin", SWORD, TEAR, "shojin", "spear of shojin"),
        recipe("Limite da Noite", SWORD, VEST, "edge of night"),
        recipe("Sedenta por Sangue", SWORD, CLOAK, "bloodthirster"),
        recipe("Sinal de Sterak", SWORD, BELT, "sterak", "sterak's gage"),
        recipe("Gume do Infinito", SWORD, GLOVE, "infinity edge", "gume"),

        recipe("Buff Vermelho", BOW, BOW, "red buff"),
        recipe("Lâmina da Fúria de Guinsoo", BOW, ROD, "guinsoo", "rageblade"),
        recipe("Faca de Statikk", BOW, TEAR, "statikk", "statikk shiv"),
        recipe("Determinação Titânica", BOW, VEST, "titan", "titan's resolve"),
        recipe("Furacão de Runaan", BOW, CLOAK, "runaan", "runaans hurricane"),
        recipe("Dente de Nashor", BOW, BELT, "nashor", "nashor's tooth"),
        recipe("Último Sussurro", BOW, GLOVE, "last whisper"),

        recipe("Capuz da Morte de Rabadon", ROD, ROD, "rabadon", "deathcap"),
        recipe("Cajado do Arcanjo", ROD, TEAR, "archangel", "archangel's staff"),
        recipe("Guarda da Coroa", ROD, VEST, "crownguard"),
        recipe("Centelha Iônica", ROD, CLOAK, "ionic spark"),
        recipe("Morellonomicon", ROD, BELT, "morello"),
        recipe("Manopla Adornada", ROD, GLOVE, "jeweled gauntlet"),

        recipe("Efeito Azul", TEAR, TEAR, "blue buff"),
        recipe("Voto do Protetor", TEAR, VEST, "protector's vow"),
        recipe("Elmo Adaptativo", TEAR, CLOAK, "adaptive helm"),
        recipe("Redenção", TEAR, BELT, "redemption"),
        recipe("Mão da Justiça", TEAR, GLOVE, "hand of justice"),

        recipe("Colete Espinhoso", VEST, VEST, "bramble vest"),
        recipe("Placa Gargolítica", VEST, CLOAK, "gargoyle", "gargoyle stoneplate"),
        recipe("Capa de Fogo Solar", VEST, BELT, "sunfire", "sunfire cape"),
        recipe("Coração Firme", VEST, GLOVE, "steadfast heart"),

        recipe("Garra do Dragão", CLOAK, CLOAK, "dragon's claw"),
        recipe("Manto da Equidade", CLOAK, BELT, "evenshroud"),
        recipe("Mercúrio", CLOAK, GLOVE, "quicksilver", "qss"),

        recipe("Armadura de Warmog", BELT, BELT, "warmog", "warmog's armor"),
        recipe("Quebra-Guarda", BELT, GLOVE, "guardbreaker"),
        recipe("Luvas do Ladrão", GLOVE, GLOVE, "thief's gloves")
    )

    fun find(rawName: String): ItemRecipe? {
        val query = normalize(rawName)
        if (query.isBlank()) return null
        return recipes.firstOrNull { recipe ->
            recipe.searchTerms.any { it == query }
        } ?: recipes
            .mapNotNull { recipe ->
                val score = recipe.searchTerms.maxOfOrNull { term -> similarityScore(query, term) } ?: 0
                recipe.takeIf { score >= 2 }?.let { it to score }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun recipesUsing(rawComponent: String): List<ItemRecipe> {
        val component = findComponent(rawComponent) ?: return emptyList()
        return recipes.filter { it.firstComponent == component || it.secondComponent == component }
    }

    fun findComponent(rawName: String): String? {
        val query = normalize(rawName)
        val aliases = mapOf(
            SWORD to setOf("espada", "espada gpc", "bf sword", "b f sword"),
            BOW to setOf("arco", "arco recurvo", "recurve bow"),
            ROD to setOf("bastao", "bastao desnecessariamente grande", "vara", "needlessly large rod", "rod"),
            TEAR to setOf("lagrima", "lagrima da deusa", "tear", "tear of the goddess"),
            VEST to setOf("cota", "cota de malha", "colete", "chain vest", "vest"),
            CLOAK to setOf("capa", "capa negatron", "negatron cloak", "cloak"),
            BELT to setOf("cinto", "cinto do gigante", "giants belt", "giant belt", "belt"),
            GLOVE to setOf("luva", "luvas", "luvas do pugilista", "sparring gloves", "glove")
        )
        return aliases.entries.firstOrNull { (_, values) -> values.map(::normalize).any { it == query } }?.key
    }

    fun describe(recipe: ItemRecipe): String =
        "${recipe.name} é feito com ${recipe.firstComponent} e ${recipe.secondComponent}."

    fun catalogSummary(): String = recipes.joinToString("\n") { recipe ->
        "${recipe.name}: ${recipe.firstComponent} + ${recipe.secondComponent}"
    }

    private val ItemRecipe.searchTerms: Set<String>
        get() = (aliases + name).map(::normalize).toSet()

    private fun recipe(
        name: String,
        first: String,
        second: String,
        vararg aliases: String
    ) = ItemRecipe(name, first, second, aliases.toSet())

    private fun similarityScore(query: String, term: String): Int = when {
        term.startsWith(query) || query.startsWith(term) -> 4
        query in term || term in query -> 3
        query.split(' ').any { it.length >= 4 && it in term } -> 2
        else -> 0
    }

    fun normalize(raw: String): String {
        val noAccents = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
