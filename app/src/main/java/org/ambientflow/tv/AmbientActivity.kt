package org.ambientflow.tv

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.*

class AmbientActivity:Activity() {
    private lateinit var session:AmbientSession
    override fun onCreate(b:Bundle?) { super.onCreate(b); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); window.decorView.systemUiVisibility=5894; session=AmbientSession(this) { finish() }; setContentView(session.root) }
    override fun onResume() { super.onResume(); session.start() }
    override fun onPause() { session.stop(); super.onPause() }
    override fun onDestroy() { session.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onDestroy() }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean = session.key(event)||super.dispatchKeyEvent(event)
}
class AmbientDreamService:DreamService() {
    private var session:AmbientSession?=null
        private var dreaming=false
    override fun onAttachedToWindow() { super.onAttachedToWindow(); isInteractive=true; isFullscreen=true; isScreenBright=false; session=AmbientSession(this) { finish() }; setContentView(session!!.root) }
    override fun onDreamingStarted() { super.onDreamingStarted(); dreaming=true; session?.start() }
    override fun onDreamingStopped() { dreaming=false; session?.stop(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { session?.stop(); session=null; super.onDetachedFromWindow() }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean = session?.key(event)==true||super.dispatchKeyEvent(event)
    override fun onWindowFocusChanged(hasFocus:Boolean) { super.onWindowFocusChanged(hasFocus); if(!hasFocus) session?.stop() else if(dreaming) session?.start() }
}
class ImageDetailsActivity:Activity() {
    override fun onCreate(b:Bundle?) {
        super.onCreate(b)
        val col=TvUi.page(this,intent.getStringExtra("title")?:"Image details","4K cityscape · source information")
        col.addView(TvUi.text(this,"Creator / credit: ${intent.getStringExtra("creator")}\nLicense: ${intent.getStringExtra("license")}\nDisplay: native-resolution 3840×2160 crop, optional pan/zoom. CC0 / public-domain source; no on-screen credit is required."))
        for((label,key) in listOf("Original and full source details" to "source","License terms" to "licenseUrl")) {
            val url=intent.getStringExtra(key)?:continue
            col.addView(TvUi.text(this,url,16f))
            col.addView(TvUi.button(this,label) { try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:ActivityNotFoundException) { android.widget.Toast.makeText(this,"No web browser installed; the full link is shown above",android.widget.Toast.LENGTH_LONG).show() } })
        }
        col.addView(TvUi.button(this,"Back to ambient") { finish() })
    }
}
