package eu.domob.heliodos

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.rarepebble.colorpicker.ColorPickerView

class ColorSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreferenceCompat(context, attrs) {

    var colorKey: String = ""
        private set
    var defaultColor: Int = Color.GRAY
        private set

    private var swatchView: View? = null
    private var switchView: SwitchCompat? = null

    init {
        layoutResource = R.layout.color_switch_preference
        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.ColorSwitchPreference, 0, 0)
            try {
                colorKey = a.getString(R.styleable.ColorSwitchPreference_colorKey) ?: ""
                defaultColor = a.getColor(R.styleable.ColorSwitchPreference_defaultColor, Color.GRAY)
            } finally {
                a.recycle()
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val sw = holder.findViewById(R.id.colorSwitch) as? SwitchCompat
        switchView = sw
        sw?.apply {
            setOnCheckedChangeListener(null)
            isChecked = this@ColorSwitchPreference.isChecked
            setOnClickListener { this@ColorSwitchPreference.performClick() }
        }

        val swatch = holder.findViewById(R.id.colorSwatch)
        swatchView = swatch
        updateSwatchColor()
        swatch?.setOnClickListener { showColorPicker() }
    }

    private fun updateSwatchColor() {
        val color = getPersistedColor()
        swatchView?.backgroundTintList = ColorStateList.valueOf(color)
    }

    fun getPersistedColor(): Int {
        if (colorKey.isEmpty()) return defaultColor
        val prefs = preferenceManager.sharedPreferences ?: return defaultColor
        return prefs.getInt(colorKey, defaultColor)
    }

    private fun showColorPicker() {
        val picker = ColorPickerView(context)
        picker.setColor(getPersistedColor())
        picker.showAlpha(false)
        picker.showHex(false)

        AlertDialog.Builder(context)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = picker.color
                preferenceManager.sharedPreferences
                    ?.edit()?.putInt(colorKey, color)?.apply()
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
