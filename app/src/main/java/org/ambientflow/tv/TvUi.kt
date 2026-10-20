package org.ambientflow.tv

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*

object TvUi {
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun text(c:Context,value:String,size:Float=20f)=TextView(c).apply { text=value; textSize=size; setTextColor(0xffe0e9e6.toInt()); setPadding(0,8,0,12) }
    fun button(c:Context,label:String,action:()->Unit)=Button(c).apply {
        text=label; textSize=18f; isAllCaps=false; minHeight=dp(c,54); setPadding(20,8,20,8)
        setTextColor(Color.WHITE)
        fun bg(focus:Boolean)=GradientDrawable().apply { cornerRadius=12f; setColor(if(focus) 0xff345a60.toInt() else 0xff1a2935.toInt()); setStroke(if(focus) 3 else 1,if(focus) 0xffc7e8dc.toInt() else 0xff34424e.toInt()) }
        background=bg(false); setOnFocusChangeListener { _,f -> background=bg(f) }; setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(c,8) }
    }
    fun page(a:Activity,title:String,subtitle:String):LinearLayout {
        val scroll=ScrollView(a).apply { isFillViewport=true }; val side=if(a.resources.configuration.screenWidthDp<700) 20 else 56; val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(a,side),dp(a,24),dp(a,side),dp(a,32)); addView(text(a,title,32f)); addView(text(a,subtitle,17f)) }
        scroll.addView(col); a.setContentView(scroll); return col
    }
    fun saver(c:Context) { try { c.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(_:ActivityNotFoundException) { AlertDialog.Builder(c).setTitle("Screensaver setup").setMessage("This TV does not expose the standard screensaver menu. See README for inspected, reversible ADB setup. No system packages need to be disabled.").setPositiveButton("OK",null).show() } }
}
class MainActivity:Activity() {
    override fun onCreate(b:Bundle?) { super.onCreate(b) }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val p=Prefs(this); val col=TvUi.page(this,"Ambient Flow","A quieter canvas for your living room")
        col.addView(TvUi.button(this,"Flow  ·  Moving filament sculptures" + if(p.str("mode","Flow")=="Flow") "  ✓" else "") { p.put("mode","Flow"); render() })
        col.addView(TvUi.button(this,"Cityscapes  ·  4K city photography" + if(p.str("mode","Flow")=="Cityscapes") "  ✓" else "") { p.put("mode","Cityscapes"); render() })
        col.addView(TvUi.button(this,"Music: ${if(p.bool("music")) "On" else "Off"}") { p.put("music",!p.bool("music")); render() })
        col.addView(TvUi.button(this,"Start ambient display") { startActivity(Intent(this,AmbientActivity::class.java)) })
        col.addView(TvUi.button(this,"Settings") { startActivity(Intent(this,SettingsActivity::class.java)) })
        col.addView(TvUi.button(this,"Screensaver setup") { TvUi.saver(this) })
        col.getChildAt(5).requestFocus()
    }
}
class SettingsActivity:Activity() {
    private lateinit var p:Prefs
    override fun onCreate(b:Bundle?) { super.onCreate(b); p=Prefs(this); render() }
    private fun render() {
        val col=TvUi.page(this,"Make it yours","D-pad to move · OK to change · Back to return")
        fun choice(title:String,key:String,values:Array<String>,default:String) { col.addView(TvUi.button(this,"$title: ${p.str(key,default)}") { AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values,values.indexOf(p.str(key,default))) { d,i -> p.put(key,values[i]); d.dismiss(); render() }.setNegativeButton("Cancel",null).show() }) }
        fun toggle(title:String,key:String,default:Boolean=false) { col.addView(TvUi.button(this,"$title: ${if(p.bool(key,default)) "On" else "Off"}") { p.put(key,!p.bool(key,default)); render() }) }
        choice("Visual mode","mode",arrayOf("Flow","Cityscapes"),"Flow")
        choice("Flow composition","composition",FlowView.compositions,"Automatic")
        choice("Flow palette","palette",FlowView.names,"Sage")
        choice("Motion","speed",arrayOf("Still","Slow","Gentle","Normal"),"Slow")
        toggle("Reduced motion","reduced")
        choice("Slideshow interval","interval",arrayOf("30 seconds","1 minute","5 minutes"),"1 minute")
        toggle("Subtle photo movement","pan")
        col.addView(TvUi.button(this,"Cities: ${p.cities().joinToString()}") {
            val cities=arrayOf("Tokyo","New York","Hong Kong","Singapore","London","Chicago","Seoul"); val checked=BooleanArray(cities.size) { cities[it] in p.cities() }
            AlertDialog.Builder(this).setTitle("Choose cities (at least one)").setMultiChoiceItems(cities,checked) { _,i,v -> checked[i]=v }.setPositiveButton("Save") { _,_ -> val selected=cities.filterIndexed { i,_ -> checked[i] }; if(selected.isNotEmpty()) p.put("cities",selected.joinToString(",")); render() }.setNegativeButton("Cancel",null).show()
        })
        toggle("Automatic music","music")
        toggle("Mute music","muted")
        col.addView(TvUi.button(this,"Music volume: ${p.int("volume",35)}%") { val values=(0..10).map { "${it*10}%" }.toTypedArray(); AlertDialog.Builder(this).setTitle("App music volume (not TV volume)").setItems(values) { _,i -> p.put("volume",i*10); render() }.show() })
        col.addView(TvUi.button(this,"Music source") {
            AlertDialog.Builder(this).setTitle("Music source").setItems(arrayOf("Lofi Girl livestream (default)","Direct HTTPS radio URL","Choose local audio file")) { _,i -> when(i) {
                0 -> p.put("source",Prefs.MUSIC)
                1 -> { val input=EditText(this).apply { setSingleLine(); setText(p.str("source", "")); hint="https://…" }; AlertDialog.Builder(this).setTitle("Direct HTTPS audio URL").setView(input).setPositiveButton("Save") { _,_ -> val url=input.text.toString().trim(); if(url.startsWith("https://")) p.put("source",url) else Toast.makeText(this,"HTTPS URL required",Toast.LENGTH_LONG).show() }.setNegativeButton("Cancel",null).show() }
                2 -> try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),7) } catch(_:ActivityNotFoundException) { Toast.makeText(this,"No TV file picker installed. Use a direct HTTPS URL instead.",Toast.LENGTH_LONG).show() }
            } }.show()
        })
        toggle("Clock","clock"); toggle("Date","date"); toggle("24-hour clock","24hour",true)
        choice("Clock placement","placement",arrayOf("Bottom right","Bottom left","Top right","Top left"),"Bottom right")
        col.addView(TvUi.button(this,"Clear photo cache (up to 64 MiB / 12 photos)") { Thread { CityRepository.clear(this); runOnUiThread { Toast.makeText(this,"Cache cleared if no ambient session is active",Toast.LENGTH_LONG).show() } }.start() })
        col.addView(TvUi.button(this,"Licenses and source information") { AlertDialog.Builder(this).setTitle("Open source · no ads or analytics").setMessage("Ambient Flow: GPL-3.0-or-later\nNewPipeExtractor: GPL-3.0-or-later\nMedia3, OkHttp: Apache-2.0\nCity photographs: CC0 / public domain only. No credit overlay. Optional source details available through Image details. Native 4K crop, no upscaling.\nLofi Girl music is streamed, not bundled or relicensed.\nFull notices accompany the source ZIP.").setNeutralButton("Full notices") { _,_ -> startActivity(Intent(this,NoticesActivity::class.java)) }.setPositiveButton("OK",null).show() })
        col.addView(TvUi.button(this,"Done") { finish() })
    }
    @Deprecated("Platform result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==7 && resultCode==RESULT_OK) data?.data?.let { uri -> try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); p.put("source",uri.toString()) } catch(_:SecurityException) { Toast.makeText(this,"Cannot retain file access",Toast.LENGTH_LONG).show() } } }
}
