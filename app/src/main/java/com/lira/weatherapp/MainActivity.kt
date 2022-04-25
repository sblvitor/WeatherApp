package com.lira.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.lira.weatherapp.databinding.ActivityMainBinding
import com.lira.weatherapp.models.WeatherResponse
import com.lira.weatherapp.network.WeatherService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null

    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private val mLocationCallBack = object: LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation
            val latitude = mLastLocation.latitude
            Log.i("Current Latitude", "$latitude")

            val longitude = mLastLocation.longitude
            Log.i("Current Longitude", "$longitude")
            getLocationWeatherDetails(latitude, longitude)
            mFusedLocationClient.removeLocationUpdates(this)
        }
    }

    private lateinit var mSharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        // Transparent status bar and at the bottom as well
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)

        setupUI()

        if(!isLocationEnabled()){
            Toast.makeText(this, "Your location provider is turned off. Please turn on", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }else{
            Dexter.withContext(this).withPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .withListener(object: MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if(report!!.areAllPermissionsGranted()){
                            requestNewLocationData()
                        }

                        if(report.isAnyPermissionPermanentlyDenied){
                            Toast.makeText(this@MainActivity, "You have denied location permission", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
                        showRationaleDialogForPermissions()
                    }
                }).onSameThread().check()
        }

        binding?.btnRefresh?.setOnClickListener {
            requestNewLocationData()
        }
    }

    private fun isLocationEnabled(): Boolean{
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData(){
        Log.i("deb", "requestNewLocationData")
        val mLocationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallBack, Looper.myLooper()!!)
    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double){
        if(Constants.isNetworkAvailable(this)){
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service: WeatherService = retrofit.create(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID)

            showCustomProgressDialog()

            listCall.enqueue(object: Callback<WeatherResponse>{
                override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                    if(response.isSuccessful){
                        cancelProgressDialog()
                        val weatherList: WeatherResponse? = response.body()
                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()
                        setupUI()
                        Log.i("Response Result", "$weatherList")
                    }else{
                        cancelProgressDialog()
                        when(response.code()){
                            400 -> {
                                Log.e("Error 400", "Bad Connection")
                            }
                            404 -> {
                                Log.e("Error 404", "Not Found")
                            }else -> {
                                Log.e("Error", "Generic Error")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("Errorrrrr", t.message.toString())
                    cancelProgressDialog()
                }
            })

        }else{
            Toast.makeText(this@MainActivity, "No internet connection available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUI(){

        val weatherResponseJsonString = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, "")

        if(!weatherResponseJsonString.isNullOrEmpty()){
            val weatherList = Gson().fromJson(weatherResponseJsonString, WeatherResponse::class.java)
            for(i in weatherList!!.weather.indices){
                Log.i("Weather name", weatherList.weather.toString())
                binding?.tvMain?.text = weatherList.weather[i].main
                binding?.tvTemp?.text = weatherList.main.temp.toInt().toString() + "ºC"
                binding?.tvMax?.text = weatherList.main.temp_max.toInt().toString() + "ºC"
                binding?.tvMin?.text = weatherList.main.temp_min.toInt().toString() + "ºC"

                binding?.cityLocation?.text = weatherList.name
                binding?.countryLocation?.text = weatherList.sys.country
                binding?.date?.text = unixTime(weatherList.sys.sunrise)
                binding?.tvWind?.text = weatherList.wind.speed.toString() + "km/h"
                binding?.tvVisibility?.text = weatherList.visibility.toString() + "km"
                binding?.tvHumidity?.text = weatherList.main.humidity.toString() + "%"
                binding?.tvAirPressure?.text = weatherList.main.pressure.toString() + "mb"

                when(weatherList.weather[i].icon){
                    "01d" -> binding?.ivWeather?.setImageResource(R.drawable.sun)
                    "01n" -> binding?.ivWeather?.setImageResource(R.drawable.moon)
                    "02d" -> binding?.ivWeather?.setImageResource(R.drawable.sun_and_cloud)
                    "02n" -> binding?.ivWeather?.setImageResource(R.drawable.moon_cloud)
                    "03d" -> binding?.ivWeather?.setImageResource(R.drawable.cloud)
                    "03n" -> binding?.ivWeather?.setImageResource(R.drawable.cloud)
                    "04d" -> binding?.ivWeather?.setImageResource(R.drawable.cloudy2)
                    "04n" -> binding?.ivWeather?.setImageResource(R.drawable.cloudy2)
                    "09d" -> binding?.ivWeather?.setImageResource(R.drawable.rain)
                    "09n" -> binding?.ivWeather?.setImageResource(R.drawable.rain)
                    "10d" -> binding?.ivWeather?.setImageResource(R.drawable.rain_sun)
                    "10n" -> binding?.ivWeather?.setImageResource(R.drawable.rainy_night)
                    "11d" -> binding?.ivWeather?.setImageResource(R.drawable.thunderstorm)
                    "11n" -> binding?.ivWeather?.setImageResource(R.drawable.thunderstorm)
                    "13d" -> binding?.ivWeather?.setImageResource(R.drawable.snowflake)
                    "13n" -> binding?.ivWeather?.setImageResource(R.drawable.snowflake)
                    "50d" -> binding?.ivWeather?.setImageResource(R.drawable.mist)
                    "50n" -> binding?.ivWeather?.setImageResource(R.drawable.mist)
                }
            }
        }
    }

    private fun unixTime(timex: Long): String?{
        val date = Date(timex * 1000L)
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.UK)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }

    private fun showRationaleDialogForPermissions(){
        AlertDialog.Builder(this).setMessage("It looks like you have turned off permission required for this feature. It can be enabled under the Application Settings")
            .setPositiveButton("Go to SETTINGS") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException){
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel") {dialog, _ ->
                dialog.dismiss()
            }.create().show()
    }

    private fun showCustomProgressDialog(){
        mProgressDialog = Dialog(this)
        mProgressDialog!!.setContentView(R.layout.custom_progress_dialog)
        // mProgressDialog!!.window!!.setBackgroundDrawableResource(android.R.color.transparent)
        mProgressDialog!!.show()
    }

    private fun cancelProgressDialog(){
        if(mProgressDialog != null){
            mProgressDialog!!.dismiss()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

}