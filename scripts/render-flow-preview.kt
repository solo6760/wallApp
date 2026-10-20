// Host-only preview of the exact Android-free geometry (no copied assets).
// kotlinc app/src/main/java/org/ambientflow/tv/FilamentGeometry.kt scripts/render-flow-preview.kt -include-runtime -d /tmp/flow-preview.jar
// java -Djava.awt.headless=true -jar /tmp/flow-preview.jar
import org.ambientflow.tv.FilamentComposition
import org.ambientflow.tv.FilamentGeometry
import org.ambientflow.tv.FilamentStyle
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private fun mix(a: Int, b: Int, t: Float): Color {
    fun channel(s: Int): Int = (((a ushr s) and 255) + (((b ushr s) and 255) - ((a ushr s) and 255)) * t).roundToInt()
    return Color(channel(16), channel(8), channel(0))
}

private fun render(seconds: Double, composition: Int = 0, automatic: Boolean = false): BufferedImage {
    val w = 960
    val h = 540
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val c = FilamentStyle.sagePalette
    g.color = Color(c[0]); g.fillRect(0, 0, w, h)
    fun patch(x: Float, y: Float, r: Float, color: Color) {
        g.paint = RadialGradientPaint(x*w, y*h, r*w, floatArrayOf(0f, 1f), arrayOf(color, Color(0, 0, 0, 0)))
        g.fillRect(0, 0, w, h)
    }
    patch(.28f, .28f, .64f, mix(c[0], c[2], .14f))
    patch(.76f, .88f, .57f, Color(14, 33, 26, 36))
    patch(.88f, .08f, .44f, mix(c[0], c[1], .08f))
    val geometry = FilamentGeometry()
    if (automatic) {
        val transition = FilamentComposition()
        transition.update(seconds, 0.0)
        geometry.update(seconds, 16.0/9.0, transition.weights)
    } else geometry.update(seconds, composition = composition)
    for (highlight in listOf(false, true)) for (s in geometry.strands) {
        val color = when (s.colorSlot) {
            0 -> Color(c[1]); 1 -> Color(c[3]); 2 -> Color(c[2])
            else -> if (highlight) Color(0x263c32) else mix(c[0], 0xff172b26.toInt(), .78f)
        }
        g.color = Color(color.red, color.green, color.blue, (s.opacity * if (highlight) 95 else 190).roundToInt())
        g.stroke = BasicStroke(s.width * if (highlight) .65f else 1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val path = Path2D.Float()
        var connected = false
        for (j in 1 until geometry.sampleCount) {
            val draw = s.visible[j] && (!highlight || s.depth[j] > .42f)
            if (draw) {
                if (!connected) path.moveTo(s.x[j-1]*w, s.y[j-1]*h)
                path.curveTo(
                    (s.x[j-1]+s.tangentX(j-1))*w, (s.y[j-1]+s.tangentY(j-1))*h,
                    (s.x[j]-s.tangentX(j))*w, (s.y[j]-s.tangentY(j))*h,
                    s.x[j]*w, s.y[j]*h
                )
            }
            connected = draw
        }
        g.draw(path)
    }
    g.dispose()
    return image
}

fun main() {
    val times = doubleArrayOf(0.0, 165.0, 345.0, 525.0)
    val sheet = BufferedImage(1920, 1136, BufferedImage.TYPE_INT_RGB)
    val graphics = sheet.createGraphics()
    graphics.color = Color(0x26332b); graphics.fillRect(0, 0, sheet.width, sheet.height)
    for (i in times.indices) {
        val image = render(times[i], automatic = true)
        if (i == 0) ImageIO.write(image, "png", File("docs/flow-preview.png"))
        val x = (i % 2) * 960
        val y = (i / 2) * 568
        graphics.drawImage(image, x, y, null)
        graphics.color = Color(0xe1d9b5)
        graphics.font = Font("SansSerif", Font.PLAIN, 15)
        graphics.drawString("Automatic — motion time ${times[i].toInt()}s (Normal speed)", x+16, y+560)
    }
    graphics.dispose()
    ImageIO.write(sheet, "png", File("docs/flow-preview-contact-sheet.png"))
    val gallery = BufferedImage(1920, 1136, BufferedImage.TYPE_INT_RGB)
    val labels = gallery.createGraphics()
    labels.color = Color(0x26332b); labels.fillRect(0, 0, gallery.width, gallery.height)
    labels.font = Font("SansSerif", Font.PLAIN, 17)
    for (i in 0..3) {
        val x = (i % 2) * 960
        val y = (i / 2) * 568
        labels.drawImage(render(0.0, composition = i), x, y, null)
        labels.color = Color(0xe1d9b5)
        labels.drawString(FilamentComposition.names[i+1], x+16, y+560)
    }
    labels.dispose()
    ImageIO.write(gallery, "png", File("docs/flow-compositions.png"))
    println("Rendered docs/flow-preview.png, docs/flow-preview-contact-sheet.png, docs/flow-compositions.png")
}
