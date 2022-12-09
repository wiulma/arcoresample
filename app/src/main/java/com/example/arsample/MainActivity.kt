package com.example.arsample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.arsample.ui.main.SceneArFragment

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SceneArFragment())
                .commitNow()
        }
    }
}