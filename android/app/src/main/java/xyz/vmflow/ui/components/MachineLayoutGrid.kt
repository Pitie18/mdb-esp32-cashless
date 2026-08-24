package xyz.vmflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.vmflow.BuildConfig
import xyz.vmflow.data.MachineAnalysis

/**
 * The physical machine layout, drawn as a springboard-style grid — the shared
 * Android counterpart of `ios/VMflow/Views/Refill/MachineLayoutGrid.swift`.
 *
 * Extracted verbatim from `MachineAnalysisView.kt`'s private `AnalysisLayoutGrid`
 * / `AnalysisGridCell` / `buildGridEntries` so the refill step can show the same
 * grid. **The component itself decides nothing about colour**: a caller resolves
 * every cell's fill (and its optional outline) and hands it down in
 * [MachineLayoutCell], because the two callers colour by unrelated things — the
 * analysis tab by a product's performance tier, the refill step by a slot's fill
 * state. That keeps the tier palette out of the refill module and the stock
 * palette out of the analysis module, and it is why this file imports no colour
 * token at all.
 *
 * The geometry (row / column / width per `item_number`) is **not** duplicated
 * here: it lives in [MachineAnalysis] (`slotRowCol` / `computeSlotWidths`), is
 * unit-tested there, and both callers build their cells from it.
 */

/** Layout-grid cell height in dp. Matches the pre-extraction analysis grid. */
private const val GRID_CELL_HEIGHT_DP = 44

/** Gap between cells, horizontally and vertically. */
private const val GRID_SPACING_DP = 4

private val CELL_SHAPE = RoundedCornerShape(6.dp)

/**
 * One occupied slot as the grid draws it. Purely presentational: the caller has
 * already resolved the geometry (from [MachineAnalysis]), the colours, and the
 * spoken description, so this grid never has to know what a "tier" or a "fill
 * state" is.
 *
 * @param id stable identity for the grid's item key and for [MachineLayoutGrid]'s
 *   click callback — both callers use the tray id.
 * @param background the cell fill, alpha included. Fixed tokens only, never a
 *   `MaterialTheme.colorScheme` hue role: this brand's primary/secondary/tertiary
 *   collapse into near-identical tones in dark mode, which is useless for a grid
 *   whose whole job is to be read at a glance.
 * @param outline drawn as a 2 dp ring on top of the cell when non-null — the
 *   refill step's "this is the slot you are working on" marker. The analysis tab
 *   passes null, which makes the modifier chain identical to the pre-extraction
 *   one.
 * @param contentDescription spoken for the cell. Never null: every cell is
 *   tappable, and colour alone must not carry the state.
 */
data class MachineLayoutCell(
    val id: String,
    val itemNumber: Int,
    val row: Int,
    val column: Int,
    val width: Int,
    val imagePath: String?,
    val background: Color,
    val outline: Color? = null,
    val contentDescription: String,
)

/** One cell of the flattened row-major grid; `cell == null` is a gap/spacer column. */
private data class GridCellEntry(
    val id: String,
    val span: Int,
    val cell: MachineLayoutCell?,
    val isGap: Boolean,
)

/**
 * Walks columns 0..9 for every row, same algorithm as iOS's
 * `MachineLayoutGrid.columnContent`: an occupied slot emits one entry spanning
 * its width and advances the column cursor by that width; an unoccupied column
 * between occupied slots emits a visible gap; columns past the last occupied
 * slot in the row emit an invisible spacer. Every row's spans sum to exactly
 * [MachineAnalysis.COLUMNS_PER_ROW], so a plain `GridCells.Fixed` grid renders
 * each machine row as one grid row.
 */
private fun buildGridEntries(rowCount: Int, cells: List<MachineLayoutCell>): List<GridCellEntry> {
    val entries = mutableListOf<GridCellEntry>()
    for (row in 0 until rowCount) {
        val rowCells = cells.filter { it.row == row }.sortedBy { it.column }
        val lastOccupied = rowCells.lastOrNull()?.let { it.column + it.width - 1 } ?: -1
        var column = 0
        var index = 0
        while (column < MachineAnalysis.COLUMNS_PER_ROW) {
            if (index < rowCells.size && rowCells[index].column == column) {
                val cell = rowCells[index]
                entries += GridCellEntry(id = "slot-${cell.id}", span = cell.width, cell = cell, isGap = false)
                column += cell.width
                index++
            } else {
                entries += GridCellEntry(id = "gap-$row-$column", span = 1, cell = null, isGap = column <= lastOccupied)
                column++
            }
        }
    }
    return entries
}

/**
 * The layout grid. [rowCount] machine rows of [MachineAnalysis.COLUMNS_PER_ROW]
 * columns, sized to exactly the height its rows need — it never scrolls itself.
 *
 * @param onCellClick receives the tapped cell's [MachineLayoutCell.id]. Gaps and
 *   spacers are inert.
 */
@Composable
fun MachineLayoutGrid(
    rowCount: Int,
    cells: List<MachineLayoutCell>,
    onCellClick: (cellId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(rowCount, cells) { buildGridEntries(rowCount, cells) }
    val gridHeight = (GRID_CELL_HEIGHT_DP * rowCount + GRID_SPACING_DP * (rowCount - 1).coerceAtLeast(0)).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(MachineAnalysis.COLUMNS_PER_ROW),
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        userScrollEnabled = false,
    ) {
        // Every cell is pinned to the same height the container was sized from.
        // Without this the cells size to their own content, the container stays
        // at the computed height, and the difference shows up as a large empty
        // gap between the grid and whatever follows it.
        items(
            entries,
            key = { it.id },
            span = { GridItemSpan(it.span) },
        ) { entry ->
            val cell = entry.cell
            if (cell != null) {
                MachineLayoutGridCell(cell = cell, onClick = { onCellClick(cell.id) })
            } else if (entry.isGap) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GRID_CELL_HEIGHT_DP.dp)
                        .clip(CELL_SHAPE)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                )
            } else {
                Spacer(modifier = Modifier.fillMaxWidth().height(GRID_CELL_HEIGHT_DP.dp))
            }
        }
    }
}

@Composable
private fun MachineLayoutGridCell(cell: MachineLayoutCell, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GRID_CELL_HEIGHT_DP.dp)
            .clip(CELL_SHAPE)
            .background(cell.background)
            // `then(Modifier)` when there is no outline: the analysis tab's
            // chain then matches the pre-extraction one exactly.
            .then(
                cell.outline
                    ?.let { Modifier.border(width = 2.dp, color = it, shape = CELL_SHAPE) }
                    ?: Modifier,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = cell.contentDescription },
    ) {
        val imagePath = cell.imagePath
        if (!imagePath.isNullOrEmpty()) {
            AsyncImage(
                model = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/product-images/$imagePath",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = cell.itemNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
