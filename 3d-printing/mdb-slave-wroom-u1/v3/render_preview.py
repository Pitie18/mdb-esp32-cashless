"""Apercu PNG du boitier v3 (vue eclatee + dessus). Lancer avec freecad.exe (GUI) :
  "C:\\Program Files\\FreeCAD 1.1\\bin\\freecad.exe" render_preview.py
"""
import os

import FreeCAD as App
import FreeCADGui as Gui
import Import
import Part

HERE = r"Z:\GLENN\#Github\mdb-esp32-cashless\3d-printing\mdb-slave-wroom-u1\v3"
STEP_PATH = os.path.join(HERE, "..", "..", "..", "kicad", "mdb_slave_esp32s3-wroom-1u", "wroom-u1.step")
PCB_Z, LIFT = 7.0, 30.0

doc = App.newDocument("apercu")
Import.insert(STEP_PATH, doc.Name)
for o in doc.Objects:
    if o.TypeId == "App::Part" and not o.InList:
        o.Placement.Base = App.Vector(0, 0, PCB_Z)

fond = doc.addObject("Part::Feature", "Fond")
fond.Shape = Part.read(os.path.join(HERE, "fond.step"))
lid = doc.addObject("Part::Feature", "Couvercle")
lid.Shape = Part.read(os.path.join(HERE, "couvercle.step"))
doc.recompute()

fond.ViewObject.ShapeColor = (0.30, 0.36, 0.42)
lid.ViewObject.ShapeColor = (0.70, 0.80, 0.85)
lid.ViewObject.Transparency = 35

view = Gui.ActiveDocument.ActiveView
view.setAxisCross(False)
bg = "#F4F5F7"

lid.Placement.Base = App.Vector(0, 0, LIFT)
view.viewIsometric()
view.fitAll()
view.saveImage(os.path.join(HERE, "apercu_eclate.png"), 2000, 1200, bg)

lid.Placement.Base = App.Vector(0, 0, 0)
lid.ViewObject.Transparency = 0
view.viewTop()
view.fitAll()
view.saveImage(os.path.join(HERE, "apercu_dessus.png"), 2000, 900, bg)

view.viewFront()
view.fitAll()
view.saveImage(os.path.join(HERE, "apercu_face_sud.png"), 2000, 600, bg)

view.viewRear()
view.fitAll()
view.saveImage(os.path.join(HERE, "apercu_face_nord.png"), 2000, 600, bg)

view.viewLeft()
view.fitAll()
view.saveImage(os.path.join(HERE, "apercu_cote_usb.png"), 1200, 900, bg)

Gui.getMainWindow().close()
