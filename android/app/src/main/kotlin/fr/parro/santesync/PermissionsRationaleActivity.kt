package fr.parro.santesync

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Écran de politique d'utilisation des données, exigé par Health Connect. */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(TextView(this@PermissionsRationaleActivity).apply {
                setPadding(pad, pad, pad, pad)
                textSize = 16f
                setText(R.string.rationale)
            })
        })
    }
}
