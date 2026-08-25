package xyz.vmflow.data

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Product
import xyz.vmflow.models.ProductCategory
import xyz.vmflow.models.Tray

/**
 * Ported from `ios/VMflow/Views/Refill/ReplacementProductPicker.swift`:
 * `fuzzyMatch` (L85-99), `stockBuckets` (L114-147), `groupByCategory`
 * (L152-255), `sortKey` (L170-177), `slotBadgeLabel` (L14-23) and
 * `ReviewStepView.existingSlots(forTrayId:)` (L316-331).
 *
 * Every ordering assertion pins the locale via an explicit comparator, so
 * these do not depend on the JVM's default locale.
 */
class ReplacementPickerLogicTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private val germanNames: Comparator<String?> = nullsLast(
        Comparator(Collator.getInstance(Locale.GERMAN).apply {
            strength = Collator.SECONDARY
        }::compare)
    )

    private fun product(
        id: String,
        name: String? = "Product $id",
        category: String? = null,
        imagePath: String? = null
    ) = Product(id = id, name = name, imagePath = imagePath, category = category)

    private fun category(id: String, name: String? = "Category $id") =
        ProductCategory(id = id, name = name)

    private fun tray(id: String, itemNumber: Int, productId: String?) =
        Tray(id = id, machineId = "m1", itemNumber = itemNumber, productId = productId)

    private fun buckets(
        products: List<Product>,
        categories: List<ProductCategory> = emptyList(),
        stock: Map<String, Int> = emptyMap(),
        stockKnown: Boolean = true,
        existingSlots: Map<String, List<Int>> = emptyMap(),
        currentCategoryId: String? = null,
        query: String = ""
    ) = ReplacementPickerLogic.buildBuckets(
        products = products,
        categories = categories,
        stockByProduct = stock,
        stockKnown = stockKnown,
        existingSlotsByProduct = existingSlots,
        currentCategoryId = currentCategoryId,
        query = query,
        nameComparator = germanNames
    )

    private fun PickerStockBucket.productIds(): List<String> =
        groups.flatMap { group -> group.rows.map { it.productId } }

    // ─── fuzzyScore ──────────────────────────────────────────────────────

    @Test
    fun `fuzzyScore is zero for a prefix match`() {
        assertEquals(0, ReplacementPickerLogic.fuzzyScore("mar", "mars"))
    }

    @Test
    fun `fuzzyScore counts skipped characters`() {
        // "ms" in "mars": 'm' at 0 (no skip), 's' at 3 with 'a','r' skipped.
        assertEquals(2, ReplacementPickerLogic.fuzzyScore("ms", "mars"))
    }

    @Test
    fun `fuzzyScore requires the characters in order`() {
        assertNull(ReplacementPickerLogic.fuzzyScore("sm", "mars"))
    }

    @Test
    fun `fuzzyScore rejects a character that is not there`() {
        assertNull(ReplacementPickerLogic.fuzzyScore("marz", "mars"))
    }

    @Test
    fun `fuzzyScore matches an empty query against anything`() {
        assertEquals(0, ReplacementPickerLogic.fuzzyScore("", "mars"))
    }

    // ─── slotBadgeParts ──────────────────────────────────────────────────

    @Test
    fun `slotBadgeParts is null for no slots`() {
        assertNull(ReplacementPickerLogic.slotBadgeParts(emptyList()))
    }

    @Test
    fun `slotBadgeParts shows a single slot`() {
        val parts = ReplacementPickerLogic.slotBadgeParts(listOf(3))!!
        assertEquals(listOf(3), parts.shown)
        assertEquals(0, parts.overflow)
    }

    @Test
    fun `slotBadgeParts sorts and shows up to three slots`() {
        val parts = ReplacementPickerLogic.slotBadgeParts(listOf(9, 1, 5))!!
        assertEquals(listOf(1, 5, 9), parts.shown)
        assertEquals(0, parts.overflow)
    }

    @Test
    fun `slotBadgeParts overflows past three slots`() {
        val parts = ReplacementPickerLogic.slotBadgeParts(listOf(12, 2, 4, 6, 8, 10))!!
        assertEquals(listOf(2, 4, 6), parts.shown)
        assertEquals(3, parts.overflow)
    }

    // ─── existingSlotsByProduct ──────────────────────────────────────────

    @Test
    fun `existingSlotsByProduct excludes the target tray and sorts slots`() {
        val trays = listOf(
            tray("t1", itemNumber = 31, productId = "p1"),
            tray("t2", itemNumber = 11, productId = "p1"),
            tray("t3", itemNumber = 12, productId = "p2"),
            // The slot being replaced: never its own "already in" badge.
            tray("target", itemNumber = 15, productId = "p1"),
            // An empty slot contributes nothing.
            tray("t4", itemNumber = 16, productId = null)
        )

        val result = ReplacementPickerLogic.existingSlotsByProduct(trays, "target")

        assertEquals(listOf(11, 31), result["p1"])
        assertEquals(listOf(12), result["p2"])
        assertEquals(2, result.size)
    }

    // ─── stock buckets ───────────────────────────────────────────────────

    @Test
    fun `products with no warehouse stock go to the out-of-stock bucket`() {
        val result = buckets(
            products = listOf(product("a"), product("b")),
            stock = mapOf("a" to 5)
        )

        assertEquals(2, result.size)
        assertEquals(PickerStockBucketKind.IN_STOCK, result[0].kind)
        assertEquals(listOf("a"), result[0].productIds())
        assertEquals(PickerStockBucketKind.OUT_OF_STOCK, result[1].kind)
        // Absent from a loaded stock map counts as zero, like
        // `RefillTourLogic.remainingWarehouseStock`.
        assertEquals(listOf("b"), result[1].productIds())
    }

    @Test
    fun `unknown stock leaves one bucket and no stock values`() {
        val result = buckets(
            products = listOf(product("a"), product("b")),
            stock = emptyMap(),
            stockKnown = false
        )

        assertEquals(1, result.size)
        assertEquals(PickerStockBucketKind.IN_STOCK, result[0].kind)
        assertTrue(result[0].groups.single().rows.all { it.warehouseStock == null })
    }

    @Test
    fun `a known stock map fills the row's stock value`() {
        val result = buckets(products = listOf(product("a")), stock = mapOf("a" to 7))
        assertEquals(7, result[0].groups.single().rows.single().warehouseStock)
    }

    @Test
    fun `empty buckets are dropped`() {
        val result = buckets(products = listOf(product("a")), stock = mapOf("a" to 1))
        assertEquals(1, result.size)
        assertEquals(PickerStockBucketKind.IN_STOCK, result[0].kind)
    }

    @Test
    fun `bucket totalCount counts every group's rows`() {
        // Two of the three products deliberately share a category, so the row
        // count (3) and the group count (2) differ. With one row per group the
        // two numbers coincide and this test passes just as happily against
        // `groups.size` as against `groups.sumOf { it.rows.size }` — i.e. it
        // cannot fail for the bug it exists to catch.
        val result = buckets(
            products = listOf(
                product("a", category = "c1"),
                product("b", category = "c1"),
                product("c", category = "c2")
            ),
            categories = listOf(category("c1"), category("c2")),
            stock = mapOf("a" to 1, "b" to 1, "c" to 1)
        )
        assertEquals(3, result.single().totalCount)
        assertEquals(2, result.single().groups.size)
    }

    // ─── category grouping ───────────────────────────────────────────────

    @Test
    fun `the current category comes first and is marked`() {
        val result = buckets(
            products = listOf(
                product("a", name = "Apple", category = "drinks"),
                product("b", name = "Banana", category = "snacks")
            ),
            categories = listOf(
                category("drinks", "Drinks"),
                category("snacks", "Snacks")
            ),
            stock = mapOf("a" to 1, "b" to 1),
            currentCategoryId = "snacks"
        )

        val groups = result.single().groups
        assertEquals(listOf("snacks", "drinks"), groups.map { it.key })
        assertTrue(groups[0].isCurrent)
        assertTrue(!groups[1].isCurrent)
    }

    @Test
    fun `other categories are ordered by name`() {
        val result = buckets(
            products = listOf(
                product("a", category = "c1"),
                product("b", category = "c2"),
                product("c", category = "c3")
            ),
            categories = listOf(
                category("c1", "Zucker"),
                category("c2", "Öl"),
                category("c3", "Apfel")
            ),
            stock = mapOf("a" to 1, "b" to 1, "c" to 1)
        )

        // Accent-aware: "Öl" sorts next to "O", not behind "Z".
        assertEquals(listOf("c3", "c2", "c1"), result.single().groups.map { it.key })
    }

    @Test
    fun `uncategorized comes last`() {
        val result = buckets(
            products = listOf(
                product("a", category = null),
                product("b", category = "c1")
            ),
            categories = listOf(category("c1")),
            stock = mapOf("a" to 1, "b" to 1)
        )

        val groups = result.single().groups
        assertEquals(listOf("c1", ReplacementPickerLogic.UNCATEGORIZED_KEY), groups.map { it.key })
        assertNull(groups.last().categoryId)
        assertNull(groups.last().categoryName)
    }

    @Test
    fun `a category uuid missing from the catalogue folds into uncategorized`() {
        val result = buckets(
            products = listOf(
                product("a", category = "ghost"),
                product("b", category = null)
            ),
            categories = emptyList(),
            stock = mapOf("a" to 1, "b" to 1)
        )

        val group = result.single().groups.single()
        assertEquals(ReplacementPickerLogic.UNCATEGORIZED_KEY, group.key)
        assertEquals(listOf("a", "b"), group.rows.map { it.productId })
    }

    @Test
    fun `an empty category catalogue still yields every product`() {
        val result = buckets(
            products = listOf(product("a", category = "c1"), product("b", category = "c2")),
            categories = emptyList(),
            stock = mapOf("a" to 1, "b" to 1)
        )
        assertEquals(2, result.single().groups.single().rows.size)
    }

    @Test
    fun `a current category with no products in a bucket does not appear`() {
        val result = buckets(
            products = listOf(product("a", category = "c1")),
            categories = listOf(category("c1"), category("c2")),
            stock = mapOf("a" to 1),
            currentCategoryId = "c2"
        )
        assertEquals(listOf("c1"), result.single().groups.map { it.key })
    }

    // ─── row ordering ────────────────────────────────────────────────────

    @Test
    fun `products already in the machine sort after the rest`() {
        val result = buckets(
            products = listOf(
                product("a", name = "Aaa"),
                product("b", name = "Bbb")
            ),
            stock = mapOf("a" to 1, "b" to 1),
            existingSlots = mapOf("a" to listOf(3))
        )

        assertEquals(listOf("b", "a"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `an empty slot list does not count as being in the machine`() {
        val result = buckets(
            products = listOf(product("a", name = "Aaa"), product("b", name = "Bbb")),
            stock = mapOf("a" to 1, "b" to 1),
            existingSlots = mapOf("a" to emptyList())
        )
        assertEquals(listOf("a", "b"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `a tighter search match sorts first`() {
        val result = buckets(
            products = listOf(
                product("loose", name = "Mineralwasser"),
                product("tight", name = "Mars")
            ),
            stock = mapOf("loose" to 1, "tight" to 1),
            query = "mar"
        )

        assertEquals(
            listOf("tight", "loose"),
            result.single().groups.single().rows.map { it.productId }
        )
    }

    @Test
    fun `search drops non-matching and nameless products`() {
        val result = buckets(
            products = listOf(
                product("hit", name = "Mars"),
                product("miss", name = "Cola"),
                product("nameless", name = null)
            ),
            stock = mapOf("hit" to 1, "miss" to 1, "nameless" to 1),
            query = "mars"
        )

        assertEquals(listOf("hit"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `search is case-insensitive and ignores surrounding whitespace`() {
        val result = buckets(
            products = listOf(product("hit", name = "Mars")),
            stock = mapOf("hit" to 1),
            query = "  MARS "
        )
        assertEquals(listOf("hit"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `a blank query keeps nameless products`() {
        val result = buckets(
            products = listOf(product("nameless", name = null)),
            stock = mapOf("nameless" to 1),
            query = "   "
        )
        assertEquals(listOf("nameless"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `names sort case-insensitively`() {
        val result = buckets(
            products = listOf(product("b", name = "banana"), product("a", name = "Apple")),
            stock = mapOf("a" to 1, "b" to 1)
        )
        assertEquals(listOf("a", "b"), result.single().groups.single().rows.map { it.productId })
    }

    @Test
    fun `a missing name sorts last`() {
        val result = buckets(
            products = listOf(product("nameless", name = null), product("named", name = "Zzz")),
            stock = mapOf("nameless" to 1, "named" to 1)
        )
        assertEquals(
            listOf("named", "nameless"),
            result.single().groups.single().rows.map { it.productId }
        )
    }

    /**
     * The order is total: two products that agree on every other key are
     * separated by their id, so the same input in a different sequence
     * produces the same output. Without the id tiebreaker this test fails
     * for one of the two input orders.
     */
    @Test
    fun `identical names are ordered by product id, whatever the input order`() {
        val twins = listOf(product("id-b", name = "Twin"), product("id-a", name = "Twin"))
        val stock = mapOf("id-a" to 1, "id-b" to 1)

        val forward = buckets(products = twins, stock = stock)
        val reversed = buckets(products = twins.reversed(), stock = stock)

        assertEquals(
            listOf("id-a", "id-b"),
            forward.single().groups.single().rows.map { it.productId }
        )
        assertEquals(
            forward.single().groups.single().rows.map { it.productId },
            reversed.single().groups.single().rows.map { it.productId }
        )
    }

    /**
     * Same totality requirement one level up: two categories with the same
     * name must not swap places between calls.
     */
    @Test
    fun `categories with identical names are ordered by id`() {
        val products = listOf(product("a", category = "c-b"), product("b", category = "c-a"))
        val categories = listOf(category("c-b", "Same"), category("c-a", "Same"))
        val stock = mapOf("a" to 1, "b" to 1)

        val forward = buckets(products, categories, stock)
        val reversed = buckets(products.reversed(), categories.reversed(), stock)

        assertEquals(listOf("c-a", "c-b"), forward.single().groups.map { it.key })
        assertEquals(
            forward.single().groups.map { it.key },
            reversed.single().groups.map { it.key }
        )
    }

    @Test
    fun `a duplicated category id does not drop its products`() {
        val result = buckets(
            products = listOf(product("a", category = "c1")),
            categories = listOf(category("c1", "First"), category("c1", "Second")),
            stock = mapOf("a" to 1)
        )

        val group = result.single().groups.single()
        assertEquals("c1", group.key)
        // Later duplicate wins, like iOS's `reduce(into:)`.
        assertEquals("Second", group.categoryName)
    }

    @Test
    fun `rows carry the product's image path and slot list`() {
        val result = buckets(
            products = listOf(product("a", imagePath = "a.png")),
            stock = mapOf("a" to 4),
            existingSlots = mapOf("a" to listOf(7, 2))
        )

        val row = result.single().groups.single().rows.single()
        assertEquals("a.png", row.imagePath)
        assertEquals(listOf(2, 7), row.existingSlots)
    }

    @Test
    fun `no products means no buckets`() {
        assertEquals(emptyList<PickerStockBucket>(), buckets(products = emptyList()))
    }
}
