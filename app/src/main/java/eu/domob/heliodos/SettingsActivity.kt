package eu.domob.heliodos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            setupCoordinateInput("manual_latitude", -90.0, 90.0, R.string.error_invalid_latitude)
            setupCoordinateInput("manual_longitude", -180.0, 180.0, R.string.error_invalid_longitude)
            setupNumberInput("manual_altitude")

            updateEnabledState()
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            updateEnabledState()
        }

        private fun setupNumberInput(key: String) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }

        private fun setupCoordinateInput(key: String, min: Double, max: Double, errorMsgId: Int) {
            setupNumberInput(key)
            findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                try {
                    val value = (newValue as String).toDouble()
                    if (value in min..max) {
                        true
                    } else {
                        Toast.makeText(context, errorMsgId, Toast.LENGTH_SHORT).show()
                        false
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, R.string.error_invalid_number, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        private fun updateEnabledState() {
            val useLocation = findPreference<SwitchPreferenceCompat>("use_location")?.isChecked == true
            findPreference<EditTextPreference>("manual_latitude")?.isEnabled = !useLocation
            findPreference<EditTextPreference>("manual_longitude")?.isEnabled = !useLocation
            findPreference<EditTextPreference>("manual_altitude")?.isEnabled = !useLocation
        }
    }
}
