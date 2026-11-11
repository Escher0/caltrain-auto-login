package dev.brian.caltrainwifi

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/** Small native-view helpers shared by this screen's cards and disclosures. */
class ScreenComponents(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(id: Int) = context.getColor(id)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Float = 14f, muted: Boolean = false, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color(if (muted) R.color.text_secondary else R.color.text_primary))
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    fun surface(radius: Int, fill: Int = R.color.card_background, border: Boolean = true) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color(fill))
            if (border) setStroke(dp(1), color(R.color.outline))
        }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(color(R.color.outline)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    fun disclosure(key: String, title: String, expanded: Boolean, subtitle: String? = null): Disclosure {
        val wrapper = column()
        val header = LinearLayout(context).apply {
            tag = "$key-toggle"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            isFocusable = true
            setPadding(0, dp(14), 0, dp(14))
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = attrs.getDrawable(0)
            attrs.recycle()
        }
        val labels = column()
        labels.addView(text(title, 14f, medium = true))
        val description = text(subtitle.orEmpty(), 12f, muted = true).apply {
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
            setPadding(0, dp(5), 0, 0)
        }
        labels.addView(description)
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = text("›", 22f, muted = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(arrow, LinearLayout.LayoutParams(dp(24), -2))
        val content = column().apply { tag = "$key-content"; setPadding(0, 0, 0, dp(16)) }
        wrapper.addView(header)
        wrapper.addView(content)
        val result = Disclosure(wrapper, header, content, description, arrow, title)
        header.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.addAction(if (result.expanded) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    result.setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND)
                    return true
                }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        header.setOnClickListener { result.setExpanded(!result.expanded) }
        result.setExpanded(expanded)
        return result
    }
}

class Disclosure(val view: LinearLayout, val header: View, val content: LinearLayout,
                 val subtitle: TextView, private val arrow: TextView, private val title: String) {
    val expanded: Boolean get() = content.visibility == View.VISIBLE
    fun setExpanded(value: Boolean) {
        content.visibility = if (value) View.VISIBLE else View.GONE
        arrow.text = if (value) "⌄" else "›"
        header.contentDescription = "$title, ${if (value) "expanded" else "collapsed"}"
    }
}
