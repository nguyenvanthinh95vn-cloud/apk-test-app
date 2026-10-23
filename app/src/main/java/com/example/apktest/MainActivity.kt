package com.example.apktest
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Hello anh! App test build tu GitHub Actions thanh cong."
        tv.textSize = 20f
        tv.setPadding(48, 48, 48, 48)
        setContentView(tv)
    }
}
