package jp.ac.titech.itpro.sdl.holylandcapture

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val cameraModeButton = findViewById<Button>(R.id.camera_mode)
        cameraModeButton.setOnClickListener {
            val intent = Intent(application, TakePictureActivity::class.java)
            startActivity(intent)
        }

        val appSettingButton = findViewById<Button>(R.id.app_setting)
        appSettingButton.setOnClickListener {
            val intent = Intent(application, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}
