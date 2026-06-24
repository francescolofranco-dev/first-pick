import java.awt.*
import java.awt.geom.*
val w = Frame()
w.isUndecorated = true
w.background = Color(0,0,0,0)
w.setSize(400, 400)
val shape = Area()
shape.add(Area(Rectangle(50, 50, 100, 100)))
w.shape = shape
w.isVisible = true
println(w.shape)
