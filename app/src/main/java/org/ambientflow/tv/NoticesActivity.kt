package org.ambientflow.tv

import android.app.Activity
import android.os.Bundle

class NoticesActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val page=TvUi.page(this,"Licenses","GPL-3.0-or-later · bundled dependency notices")
        page.addView(TvUi.button(this,"Back") { finish() })
        for(name in listOf("THIRD_PARTY_NOTICES.md","LICENSE")) {
            val text=assets.open(name).bufferedReader().use { it.readText() }
            page.addView(TvUi.text(this,text,16f))
        }
    }
}
