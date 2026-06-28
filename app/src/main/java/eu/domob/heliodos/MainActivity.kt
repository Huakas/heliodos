package eu.domob.heliodos

import android.Manifest
import android.animation.LayoutTransition
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.Calendar

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private lateinit var cameraFeedView: CameraFeedView
    private lateinit var overlayView: OverlayView
    private lateinit var timeSlider: Slider
    private lateinit var dateTextView: Button
    private lateinit var timeTextView: Button
    private lateinit var jumpToNowButton: Button
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var rotationSensor: Sensor? = null
    private var isTimeUpdatesStopped = false
    private var time = Calendar.getInstance()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<ConstraintLayout>(R.id.root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraFeedView = findViewById(R.id.cameraFeedView)
        overlayView = findViewById(R.id.overlayView)
        overlayView.cameraFeedView = cameraFeedView
        timeSlider = findViewById(R.id.timeSlider)
        timeSlider.value = timeToSlider()
        timeSlider.addOnChangeListener { _, f, _ ->
            updateOverlayTimeFromSlider(f)
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            isTimeUpdatesStopped = true
            showJumpToNowButton()
            time.timeInMillis -= 86400000
            updateTime()
        }
        findViewById<Button>(R.id.forwardButton).setOnClickListener {
            isTimeUpdatesStopped = true
            showJumpToNowButton()
            time.timeInMillis += 86400000
            updateTime()
        }
        jumpToNowButton = findViewById(R.id.jumpToNowButton)
        jumpToNowButton.setOnClickListener {
            time.timeInMillis = System.currentTimeMillis()
            timeSlider.value = timeToSlider()
            isTimeUpdatesStopped = false
            startTimeUpdates()
            jumpToNowButton.visibility = View.GONE
        }

        dateTextView = findViewById(R.id.dateTextView)
        timeTextView = findViewById(R.id.timeTextView)

        dateTextView.setOnClickListener {
            showDatePicker()
        }
        timeTextView.setOnClickListener {
            showTimePicker()
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private fun timeToSlider(): Float {
        var timeOfDay = time.get(Calendar.HOUR_OF_DAY) * 3600
        timeOfDay += time.get(Calendar.MINUTE) * 60
        timeOfDay += time.get(Calendar.SECOND)
        return timeOfDay / 86400f
    }

    private fun showDatePicker() {
        val year = time.get(Calendar.YEAR)
        val month = time.get(Calendar.MONTH)
        val day = time.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(this, { _, y, m, d ->
            isTimeUpdatesStopped = true
            showJumpToNowButton()
            time.set(Calendar.YEAR, y)
            time.set(Calendar.MONTH, m)
            time.set(Calendar.DAY_OF_MONTH, d)
            updateTime()
        }, year, month, day).show()
    }

    private fun showTimePicker() {
        val hour = time.get(Calendar.HOUR_OF_DAY)
        val minute = time.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, h, m ->
            isTimeUpdatesStopped = true
            showJumpToNowButton()
            time.set(Calendar.HOUR_OF_DAY, h)
            time.set(Calendar.MINUTE, m)
            updateTime()
        }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun showJumpToNowButton() {
        val layout = findViewById<LinearLayout>(R.id.textContainer)

        layout.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }
        jumpToNowButton.visibility = View.VISIBLE
    }

    private fun checkAndRequestPermissions() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useLocation = prefs.getBoolean("use_location", true)

        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (useLocation) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.indexOf(Manifest.permission.CAMERA) != -1) {
            cameraFeedView.visibility = View.GONE
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startApp()
        }
    }

    private fun startApp() {
        if (cameraFeedView.isAvailable) {
            cameraFeedView.openCamera()
        }
        updateLocationMode()
    }

    private fun updateLocationMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useLocation = prefs.getBoolean("use_location", true)

        if (useLocation) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            }
        } else {
            locationManager.removeUpdates(this)
            val latitude = prefs.getString("manual_latitude", "51.5")?.toDoubleOrNull() ?: 51.5
            val longitude = prefs.getString("manual_longitude", "0")?.toDoubleOrNull() ?: 0.0
            val altitude = prefs.getString("manual_altitude", "0")?.toDoubleOrNull() ?: 0.0
            overlayView.updatePosition(latitude, longitude, altitude)
        }
    }

    private fun startLocationUpdates() {
        overlayView.clearLocation()

        val lastKnownLocationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastKnownLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        var bestLocation = lastKnownLocationGPS
        if (bestLocation == null || (lastKnownLocationNetwork != null && lastKnownLocationNetwork.time > bestLocation.time)) {
            bestLocation = lastKnownLocationNetwork
        }

        if (bestLocation != null) {
            onLocationChanged(bestLocation)
        }

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
        }
    }

    private fun startTimeUpdates() {
        if (isTimeUpdatesStopped) return

        val currentTime = System.currentTimeMillis()

        time.timeInMillis = currentTime
        updateTime()
        Handler(Looper.getMainLooper()).postDelayed({ startTimeUpdates() }, 1000L)
    }

    private fun updateTime() {
        overlayView.updateTime(time.timeInMillis)
        dateTextView.text = "Date: ${time.get(Calendar.DAY_OF_MONTH)}.${time.get(Calendar.MONTH) + 1}.${time.get(Calendar.YEAR)}"
        timeTextView.text = "Time: ${String.format("%02d", time.get(Calendar.HOUR_OF_DAY))}:${String.format("%02d", time.get(Calendar.MINUTE))}"
    }

    private fun updateOverlayTimeFromSlider(sliderPosition: Float) {
        isTimeUpdatesStopped = true
        showJumpToNowButton()
        var secondsLeftover = sliderPosition * 86399
        val hours = (secondsLeftover / 3600).toInt()
        secondsLeftover -= hours * 3600
        val minutes = (secondsLeftover / 60).toInt()
        val seconds = (secondsLeftover - (minutes * 60)).toInt()
        time.set(Calendar.HOUR_OF_DAY, hours)
        time.set(Calendar.MINUTE, minutes)
        time.set(Calendar.SECOND, seconds)
        updateTime()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val cameraPermissionIndex = permissions.indexOf(Manifest.permission.CAMERA)
            val cameraGranted = if (cameraPermissionIndex != -1) {
                grantResults[cameraPermissionIndex] == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }

            if (!cameraGranted) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            // Check location permissions if they were requested
            val fineLocIndex = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseLocIndex = permissions.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            val locationGranted = (fineLocIndex != -1 && grantResults[fineLocIndex] == PackageManager.PERMISSION_GRANTED) ||
                                  (coarseLocIndex != -1 && grantResults[coarseLocIndex] == PackageManager.PERMISSION_GRANTED)

            if (!locationGranted && (fineLocIndex != -1 || coarseLocIndex != -1)) {
                // Location requested but denied. Switch to manual mode.
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("use_location", false).apply()
                Toast.makeText(this, "Location denied, using manual mode", Toast.LENGTH_SHORT).show()
            }

            startApp()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        cameraFeedView.closeCamera()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        checkAndRequestPermissions()
        startTimeUpdates()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            overlayView.setRotationMatrix(rotationMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    override fun onLocationChanged(location: Location) {
        overlayView.updatePosition(location.latitude, location.longitude, location.altitude)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
