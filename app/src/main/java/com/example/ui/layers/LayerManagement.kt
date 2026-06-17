package com.example.ui.layers

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Core UI Row types for flattening representation
 */
enum class RowType {
    LAYER, GROUP, SHAPE
}

/**
 * 1. CORE DATA STRUCTURE (In-Memory Tree)
 * Inspired by professional graphic editors like Adobe Illustrator.
 * Every CanvasNode has identity, layout properties, and safe parent ref.
 */
sealed class CanvasNode {
    abstract val id: String
    abstract var name: String
    abstract var isVisible: Boolean
    abstract var isLocked: Boolean
    abstract var isExpanded: Boolean
    abstract var parent: CanvasNode?

    /**
     * Leaf node representing a primitive or path shape
     */
    data class Shape(
        override val id: String,
        override var name: String,
        override var isVisible: Boolean,
        override var isLocked: Boolean,
        override var isExpanded: Boolean = false,
        override var parent: CanvasNode? = null,
        val shapeType: String
    ) : CanvasNode()

    /**
     * Composite node representing either a Layer (Top-level) or a Sub-group of nodes
     */
    data class GroupOrLayer(
        override val id: String,
        override var name: String,
        override var isVisible: Boolean,
        override var isLocked: Boolean,
        override var isExpanded: Boolean,
        override var parent: CanvasNode? = null,
        val isLayer: Boolean,
        val children: MutableList<CanvasNode> = mutableListOf()
    ) : CanvasNode()
}

/**
 * 2. UI FLAT DATA MODEL
 * Flattened row representation for optimal Recycler rendering.
 */
data class LayerRowItem(
    val nodeId: String,
    val name: String,
    val type: RowType,
    val depth: Int,
    val isExpanded: Boolean,
    val isVisible: Boolean,
    val isLocked: Boolean,
    val shapeType: String? = null
)

/**
 * 3. TREE FLATTENING LOGIC
 * Recursively flattens the nested tree structure into a flat list representing
 * only currently visible (expanded parent layout) rows.
 */
fun flattenTree(nodes: List<CanvasNode>, depth: Int = 0): List<LayerRowItem> {
    val flatList = mutableListOf<LayerRowItem>()
    for (node in nodes) {
        val type = when (node) {
            is CanvasNode.GroupOrLayer -> {
                if (node.isLayer) RowType.LAYER else RowType.GROUP
            }
            is CanvasNode.Shape -> RowType.SHAPE
        }
        val item = LayerRowItem(
            nodeId = node.id,
            name = node.name,
            type = type,
            depth = depth,
            isExpanded = node.isExpanded,
            isVisible = node.isVisible,
            isLocked = node.isLocked,
            shapeType = (node as? CanvasNode.Shape)?.shapeType
        )
        flatList.add(item)
        
        // Children of a group are only visible to the recycler list if the node itself is expanded.
        if (node is CanvasNode.GroupOrLayer && node.isExpanded) {
            flatList.addAll(flattenTree(node.children, depth + 1))
        }
    }
    return flatList
}

/**
 * 5. INTERACTION CALLBACKS
 * Standard interface for observing action events on layers.
 */
interface LayerInteractionListener {
    fun onToggleExpand(nodeId: String)
    fun onToggleVisibility(nodeId: String)
    fun onToggleLock(nodeId: String)
    fun onNodeSelected(nodeId: String)
}

/**
 * 4. UI COMPONENTS (RecyclerView & ListAdapter)
 * A clean, high-performance UI presenter designed programmatically to offer robust compile-ready layouts.
 */
class LayerListAdapter(
    private val listener: LayerInteractionListener
) : ListAdapter<LayerRowItem, RecyclerView.ViewHolder>(LayerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        
        // Programmatic horizontal container for maximum portability & compile safety
        val rowLayout = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                dpToPx(context, 48f)
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, 12f), 0, dpToPx(context, 12f), 0)
            setBackgroundColor(AndroidColor.parseColor("#1E293B"))
        }
        
        return LayerViewHolder(rowLayout, listener)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is LayerViewHolder) {
            holder.bind(getItem(position))
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            dp, 
            context.resources.displayMetrics
        ).toInt()
    }

    class LayerViewHolder(
        private val container: LinearLayout,
        private val listener: LayerInteractionListener
    ) : RecyclerView.ViewHolder(container) {

        private val indentationSpacer = View(container.context)
        
        // Use highly customizable, high-fidelity custom styled Text views for reliable rendering
        private val expandBtn = TextView(container.context).apply {
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        private val typeIcon = TextView(container.context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        private val nameTxt = TextView(container.context).apply {
            setTextColor(AndroidColor.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val spacer = View(container.context)
        private val lockBtn = TextView(container.context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        private val visibilityBtn = TextView(container.context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        init {
            val context = container.context
            
            // Build programmatic layout hierarchy safely
            container.addView(indentationSpacer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            
            val iconSize = dpToPx(context, 32f)
            val iconMargin = dpToPx(context, 4f)
            
            container.addView(expandBtn, LinearLayout.LayoutParams(dpToPx(context, 24f), iconSize).apply {
                marginEnd = iconMargin
            })
            
            container.addView(typeIcon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMargin
            })
            
            container.addView(nameTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            })
            
            container.addView(spacer, LinearLayout.LayoutParams(dpToPx(context, 8f), ViewGroup.LayoutParams.MATCH_PARENT))
            
            container.addView(lockBtn, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMargin
            })
            
            container.addView(visibilityBtn, LinearLayout.LayoutParams(iconSize, iconSize))
        }

        fun bind(item: LayerRowItem) {
            val context = container.context
            
            // Calculate dynamic indentation based on tree depth
            val indentPx = dpToPx(context, (item.depth * 16).toFloat())
            indentationSpacer.layoutParams = LinearLayout.LayoutParams(indentPx, ViewGroup.LayoutParams.MATCH_PARENT)
            
            // Bind Name
            nameTxt.text = item.name
            
            // Visibility of expand arrows (hidden for leaf Shapes)
            if (item.type == RowType.SHAPE) {
                expandBtn.visibility = View.INVISIBLE
            } else {
                expandBtn.visibility = View.VISIBLE
                expandBtn.text = if (item.isExpanded) "▼" else "▶"
                expandBtn.setOnClickListener {
                    listener.onToggleExpand(item.nodeId)
                }
            }
            
            // Type Icons using scalable high-resolution emojis which are universally consistent & beautiful
            typeIcon.text = when (item.type) {
                RowType.LAYER -> "📁"
                RowType.GROUP -> "📂"
                RowType.SHAPE -> {
                    when (item.shapeType?.uppercase()) {
                        "RECTANGLE", "RECT" -> "⬜"
                        "CIRCLE", "ELLIPSE" -> "⚪"
                        "TEXT" -> "💬"
                        else -> "✏️"
                    }
                }
            }
            
            // Standard Lock indicator
            lockBtn.text = if (item.isLocked) "🔒" else "🔓"
            lockBtn.setOnClickListener {
                listener.onToggleLock(item.nodeId)
            }
            
            // Visibility control indicator
            visibilityBtn.text = if (item.isVisible) "👁️" else "🕶️"
            visibilityBtn.setOnClickListener {
                listener.onToggleVisibility(item.nodeId)
            }
            
            // Full Node Row selected callback
            container.setOnClickListener {
                listener.onNodeSelected(item.nodeId)
            }
            
            // Visual row formatting
            if (item.type == RowType.LAYER) {
                container.setBackgroundColor(AndroidColor.parseColor("#1E293B"))
            } else {
                container.setBackgroundColor(AndroidColor.parseColor("#0F172A"))
            }
        }

        private fun dpToPx(context: Context, dp: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 
                dp, 
                context.resources.displayMetrics
            ).toInt()
        }
    }

    /**
     * Highly optimized DiffUtil Callback for smooth transition operations
     */
    class LayerDiffCallback : DiffUtil.ItemCallback<LayerRowItem>() {
        override fun areItemsTheSame(oldItem: LayerRowItem, newItem: LayerRowItem): Boolean {
            return oldItem.nodeId == newItem.nodeId
        }

        override fun areContentsTheSame(oldItem: LayerRowItem, newItem: LayerRowItem): Boolean {
            return oldItem == newItem
        }
    }
}
