package org.ambientflow.tv

import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max

class PhotoView(c:Context):View(c) {
    var current:Bitmap?=null
    var previous:Bitmap?=null
    var fadeStart=0L
    var movement=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    override fun onDraw(canvas:Canvas) {
        val elapsed=SystemClock.elapsedRealtime()-fadeStart
        fun draw(b:Bitmap,alpha:Int) {
            if(b.isRecycled) return
            val zoom=if(movement) 1f+.018f*(1-Math.cos(elapsed/60000.0)).toFloat() else 1f
            val scale=max(width.toFloat()/b.width,height.toFloat()/b.height)*zoom
            val w=b.width*scale; val h=b.height*scale
            rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
            paint.alpha=alpha; canvas.drawBitmap(b,null,rect,paint)
        }
        previous?.let { draw(it,255) }
        current?.let { draw(it,if(previous==null) 255 else (elapsed/1800f*255).toInt().coerceIn(0,255)) }
        if(elapsed<1800 && previous!=null) postInvalidateDelayed(33) else { previous=null; if(movement && current!=null) postInvalidateDelayed(66) }
    }
    fun clear() { animate().cancel(); current=null; previous=null; invalidate() }
}

/** A session owns every callback, network worker, renderer, bitmap and audio player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AmbientSession(private val context:Context, private val exit:()->Unit) {
    val root=FrameLayout(context)
    private val p=Prefs(context)
    private val flow=FlowView(context)
    private val image=PhotoView(context)
    private val credits=TvUi.text(context,"",12f)
    private val clock=TvUi.text(context,"",26f)
    private val controls=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12); setBackgroundColor(0xeb0c131c.toInt()) }
    private val buttons=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
    private val status=TvUi.text(context,"",15f)
    private val main=Handler(Looper.getMainLooper())
    private var audio:AudioController?=null
    private var repo:CityRepository?=null
    private var worker:java.util.concurrent.ExecutorService?=null
    private var active=false
    private var generation=0
    private var loading=false
    private var paused=false
    private var photo:CityPhoto?=null
    private var oldPhoto:CityPhoto?=null
    private var cityMode=false
    private val hide=Runnable { controls.visibility=View.GONE; root.requestFocus() }
    private val advance=Runnable { if(!paused) next() }
    private val tick=object:Runnable { override fun run() { if(!active)return; updateClock(); main.postDelayed(this,15000) } }
    init {
        root.addView(flow,FrameLayout.LayoutParams(-1,-1)); root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(clock,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(36,32,36,48) })
        credits.setShadowLayer(3f,0f,1f,Color.BLACK); credits.setBackgroundColor(0x78000000); credits.maxLines=3
        root.addView(credits,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { leftMargin=24; rightMargin=24; bottomMargin=8 })
        controls.addView(status)
        controls.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(buttons) })
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        fun button(label:String,action:()->Unit) { buttons.addView(TvUi.button(context,label) { action(); reveal() },LinearLayout.LayoutParams(TvUi.dp(context,140),TvUi.dp(context,58)).apply { marginEnd=6 }) }
        button("Pause") { paused=!paused; (buttons.getChildAt(0) as Button).text=if(paused) "Resume" else "Pause"; if(paused) { generation++; loading=false; main.removeCallbacks(advance); flow.stop(); image.movement=false } else { flow.start(); image.movement=p.bool("pan")&&!p.bool("reduced"); image.invalidate(); schedule() } }
        button("Next") { if(cityMode) next() }
        button("Music") { p.put("music",!p.bool("music")); syncAudio() }
        button("Mute") { p.put("muted",!p.bool("muted")); audio?.setVolume(p.int("volume",35)/100f,p.bool("muted")) }
        button("Image details") { details() }
        button("Settings") { context.startActivity(Intent(context,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        button("Exit") { exit() }
        root.isFocusableInTouchMode=true
        root.setOnClickListener { reveal() }
    }
    fun start() {
        if(active)return
        active=true; generation++; cityMode=p.str("mode","Flow")=="Cityscapes"
        image.movement=p.bool("pan")&&!p.bool("reduced")&&!paused; if(!paused) flow.start()
        worker=Executors.newSingleThreadExecutor(); if(cityMode) { repo=CityRepository(context); next() }
        audio=AudioController(context) { status.text=it }; syncAudio(); main.post(tick); reveal()
    }
    private fun syncAudio() { if(p.bool("music")) audio?.start(p.str("source",Prefs.MUSIC),p.int("volume",35)/100f,p.bool("muted")) else { audio?.stop(); status.text="Music off" } }
    private fun schedule() { main.removeCallbacks(advance); if(active&&cityMode&&!paused) main.postDelayed(advance,when(p.str("interval","1 minute")) { "30 seconds"->30000L; "5 minutes"->300000L; else->60000L }) }
    private fun next() {
        if(!active||loading)return
        loading=true; main.removeCallbacks(advance)
        val token=generation; val repository=repo?:return; val id=photo?.id; val cities=p.cities()
        worker?.execute {
            val item=try { repository.next(cities,id) } catch(_:Exception) { null }
            val bitmap=item?.let { try { repository.decode(it) } catch(_:Exception) { null } }
            main.post {
                if(!active||token!=generation) { bitmap?.recycle(); return@post }
                loading=false
                if(item!=null&&bitmap!=null) {
                    oldPhoto=photo; photo=item; image.previous=image.current; image.current=bitmap; image.fadeStart=SystemClock.elapsedRealtime(); image.invalidate()
                    credits.text=listOfNotNull(oldPhoto?.let { "${it.creator} · ${it.license}" },"${item.title.removePrefix("File:")} · ${item.creator} · ${item.license} · Wikimedia Commons · cropped to fit").joinToString("\n")
                    flow.stop()
                    main.postDelayed({ if(active&&token==generation) { oldPhoto=null; credits.text="${item.title.removePrefix("File:")} · ${item.creator} · ${item.license} · Wikimedia Commons · cropped to fit" } },1850)
                } else if(photo==null) status.text="Cityscapes unavailable · showing Flow until photos arrive"
                schedule()
            }
        }
    }
    fun stop() {
        if(!active)return
        active=false; generation++; main.removeCallbacksAndMessages(null); clock.animate().cancel(); flow.stop(); image.clear(); photo=null; oldPhoto=null
        repo?.close(); repo=null; worker?.shutdownNow(); worker=null; loading=false; audio?.release(); audio=null
    }
    fun reveal() { controls.visibility=View.VISIBLE; main.removeCallbacks(hide); main.postDelayed(hide,7000); if(!controls.hasFocus()) buttons.getChildAt(0).requestFocus() }
    fun key(event:KeyEvent):Boolean {
        if(event.action!=KeyEvent.ACTION_DOWN)return false
        if(event.keyCode==KeyEvent.KEYCODE_BACK) { exit(); return true }
        if(event.keyCode==KeyEvent.KEYCODE_MEDIA_NEXT) { next(); reveal(); return true }
        if(event.keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { buttons.getChildAt(0).performClick(); return true }
        if(event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_MENU)) {
            if(controls.visibility!=View.VISIBLE) { reveal(); return true }
            main.removeCallbacks(hide); main.postDelayed(hide,7000)
        }
        return false
    }
    private fun updateClock() {
        val now=Date(); val parts=mutableListOf<String>()
        if(p.bool("clock")) parts+=SimpleDateFormat(if(p.bool("24hour",true)) "HH:mm" else "h:mm a",Locale.getDefault()).format(now)
        if(p.bool("date")) parts+=SimpleDateFormat("EEE, MMM d",Locale.getDefault()).format(now)
        clock.text=parts.joinToString("\n"); clock.setShadowLayer(4f,0f,1f,Color.BLACK)
        val placement=p.str("placement","Bottom right")
        (clock.layoutParams as FrameLayout.LayoutParams).apply { gravity=(if(placement.startsWith("Top")) Gravity.TOP else Gravity.BOTTOM) or (if(placement.endsWith("left")) Gravity.LEFT else Gravity.RIGHT); bottomMargin=TvUi.dp(context,72); topMargin=TvUi.dp(context,32) }.also { clock.layoutParams=it }
        clock.animate().translationX(if((System.currentTimeMillis()/300000)%2==0L) 0f else -TvUi.dp(context,12).toFloat()).setDuration(5000).start()
    }
    private fun details() {
        val item=photo ?: return
        // Separate activity also works from DreamService, where application-context dialogs cannot.
        context.startActivity(Intent(context,ImageDetailsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title",item.title).putExtra("creator",item.creator).putExtra("license",item.license).putExtra("source",item.sourceUrl).putExtra("licenseUrl",item.licenseUrl))
    }
}
