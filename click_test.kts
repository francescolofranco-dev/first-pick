import java.awt.Window
import java.awt.Frame
val w = Frame()
w.isUndecorated = true
w.background = java.awt.Color(0,0,0,0)
w.setSize(200, 200)
w.isVisible = true
println(w.background)
