"""Boitier WROOM-U1 v3 (fermeture a clips seule) - script FreeCAD 1.x (Part). Unites : mm.

Lancement :
  "C:\\Program Files\\FreeCAD 1.1\\bin\\freecadcmd.exe" build_case_v3.py
Le STEP de la carte est lu depuis STEP_PATH (repere d'origine KiCad, carte centree,
face inferieure a z=0). La carte est posee a PCB_Z dans le boitier.

Sorties (dans le dossier du script) : fond.step/.stl, couvercle.step/.stl (STL pose
face exterieure sur le plateau), assemblage.step (avec la carte), verification.json.
"""
import json
import os

import FreeCAD as App
import Import
import MeshPart
import Part

V = App.Vector
HERE = r"Z:\GLENN\#Github\mdb-esp32-cashless\3d-printing\mdb-slave-wroom-u1\v3"
STEP_PATH = os.path.join(HERE, "..", "..", "..", "kicad", "mdb_slave_esp32s3-wroom-1u", "wroom-u1.step")

# ---------------------------------------------------------------- parametres
WALL = 2.0            # epaisseur parois
FLOOR = 2.0           # epaisseur fond
LID = 2.0             # epaisseur couvercle
GAP = 0.6             # jeu carte / paroi
STANDOFF = 5.0        # hauteur entretoises (broches traversantes : -2.9 mm)
PCB_Z = FLOOR + STANDOFF
PCB_T = 1.52
TOP_CLEAR = 1.4       # jeu au-dessus du composant le plus haut (relais 17.6)
INSERT_BORE = 3.2     # insert M2.5 x 5 x OD3.5 (PCB uniquement) (valider avec test_inserts.stl de la v1)
INSERT_DEPTH = 5.6
FIT = 0.25            # jeu levre couvercle / paroi

BOARD_X, BOARD_Y = 74.91, 19.25
PCB_HOLES = [(-71.414936, -15.75), (-71.414936, 15.75), (41.4757, -15.7906), (41.4857, 14.7496)]

X0, X1 = -75.6, BOARD_X + GAP          # interieur (USB-C depasse jusqu'a -75.42)
Y0, Y1 = -(BOARD_Y + 0.5), BOARD_Y + 0.5
H = PCB_Z + 17.6 + TOP_CLEAR           # hauteur du fond (haut des parois)


# Bornier WJ500V : centres des poles (repere carte). Cote = mur traverse par le fil.
def poles(first, other, side, axis="x"):
    return [((first + i * 5.08, other) if axis == "x" else (other, first + i * 5.08), side) for i in range(3)]


TERMINALS = []
for xf in (-57.79, -42.23, -26.68, -11.14):              # P4 P5 P6 P3 (sud)
    TERMINALS += poles(xf, -14.30, "S")
for xf in (9.09, 24.63):                                  # P2 P1 (nord)
    TERMINALS += poles(xf, 14.30, "N")
for yf in (-13.75, 2.74):                                 # P8 P9 (est)
    TERMINALS += poles(yf, 70.06, "E", axis="y")
WIRE_Z = (3.08 - 0.6, 9.32 + 0.6)                         # entree fil, repere carte

MDB = (10.04, 24.04, -1.9, 12.3)                          # x0 x1 z0 z1 (repere carte)
USB_C = (0.38, 2.78)                                      # centre y, z (repere carte)
LEDS = [(-47.76, -3.43)]                                  # WS2812B-2121 "LED D0"
ANTENNA_X = -46.0                                         # fente cable U.FL, mur nord
CLIP_X = (-64.5, 60.0)                                    # clips sur murs nord et sud


def box(x0, x1, y0, y1, z0, z1):
    return Part.makeBox(x1 - x0, y1 - y0, z1 - z0, V(x0, y0, z0))


def _fillet(b, r, axis):
    edges = [e for e in b.Edges if abs(getattr(e.Vertexes[0].Point - e.Vertexes[1].Point, axis)) > 1e-6]
    return b.makeFillet(r, edges)


def rbox(x0, x1, y0, y1, z0, z1, r):
    """Boite a aretes verticales arrondies."""
    r = min(r, (x1 - x0) / 2 - 0.01, (y1 - y0) / 2 - 0.01)
    return _fillet(box(x0, x1, y0, y1, z0, z1), r, "z")


def cyl(x, y, r, z0, z1):
    return Part.makeCylinder(r, z1 - z0, V(x, y, z0))


def wall_window(side, a0, a1, z0, z1, r=0.8):
    """Fenetre traversante dans un mur. a = x (murs N/S) ou y (murs E/O)."""
    r = min(r, (a1 - a0) / 2 - 0.01, (z1 - z0) / 2 - 0.01)
    if side == "S":
        return _fillet(box(a0, a1, Y0 - WALL - 1, Y0 + 1, z0, z1), r, "y")
    if side == "N":
        return _fillet(box(a0, a1, Y1 - 1, Y1 + WALL + 1, z0, z1), r, "y")
    if side == "E":
        return _fillet(box(X1 - 1, X1 + WALL + 1, a0, a1, z0, z1), r, "x")
    return _fillet(box(X0 - WALL - 1, X0 + 1, a0, a1, z0, z1), r, "x")


def insert_cut(shape, x, y, top, diam=INSERT_BORE):
    bore = cyl(x, y, diam / 2, top - INSERT_DEPTH, top + 0.1)
    lead = Part.makeCone(diam / 2, diam / 2 + 0.4, 0.4, V(x, y, top - 0.4))
    return shape.cut(bore.fuse(lead))


# ---------------------------------------------------------------- fond
OX0, OX1, OY0, OY1 = X0 - WALL, X1 + WALL, Y0 - WALL, Y1 + WALL
base = rbox(OX0, OX1, OY0, OY1, 0, H, 3.0).cut(rbox(X0, X1, Y0, Y1, FLOOR, H + 1, 1.0))

for x, y in PCB_HOLES:                                    # entretoises carte + inserts
    base = insert_cut(base.fuse(cyl(x, y, 3.2, FLOOR - 0.1, PCB_Z)), x, y, PCB_Z)

# Pattes de fixation (petits cotes) : oblong 10 x 4.5 (vis M4) + 2 fentes colson + tunnel.
EAR_T, EAR_L = 4.0, 20.0
for sgn, xw in ((-1, OX0), (1, OX1)):
    xc = xw + sgn * EAR_L / 2
    xa, xb = sorted((xw - sgn * 2, xw + sgn * EAR_L))
    ear = rbox(xa, xb, -16, 16, 0, EAR_T, 3.0)
    ear = ear.cut(box(xc - 2.25, xc + 2.25, -2.75, 2.75, -1, EAR_T + 1)
                  .fuse([cyl(xc, -2.75, 2.25, -1, EAR_T + 1), cyl(xc, 2.75, 2.25, -1, EAR_T + 1)]))
    for y in (-11.0, 11.0):
        ear = ear.cut(box(xc - 4.5, xc + 4.5, y - 1.6, y + 1.6, -1, EAR_T + 1))
    ear = ear.cut(box(xc - 4.7, xc + 4.7, -12.6, 12.6, -0.1, 1.8))       # tunnel colson
    base = base.fuse(ear)

cuts = []
# USB-C : 12.8 x 7.2, fiche surmoulee
cuts.append(wall_window("W", USB_C[0] - 6.4, USB_C[0] + 6.4,
                        PCB_Z + USB_C[1] - 3.6, PCB_Z + USB_C[1] + 3.6, 2.4))
# MDB Mini-Fit 2x3 (fiche + verrou)
cuts.append(wall_window("S", MDB[0] - 0.8, MDB[1] + 0.8, PCB_Z + PCB_T + 0.3, PCB_Z + MDB[3] + 2.0, 1.0))
# Entrees de fil borniers (une fenetre par pole)
for (px, py), side in TERMINALS:
    a = px if side in "NS" else py
    cuts.append(wall_window(side, a - 2.4, a + 2.4, PCB_Z + WIRE_Z[0], PCB_Z + WIRE_Z[1], 1.0))
# Antenne : fente 3 x 2 mm en haut du mur nord, sous le couvercle
cuts.append(box(ANTENNA_X - 1.5, ANTENNA_X + 1.5, Y1 - 1, Y1 + WALL + 1, H - 2.0, H + 1))
# Ventilation basse : fentes verticales 1.6 x 3.4 sous la carte, murs N et S
for x in [i * 5.0 for i in range(-14, 15)]:
    for side in "NS":
        cuts.append(wall_window(side, x - 0.8, x + 0.8, FLOOR + 0.8, PCB_Z - 0.8, 0.7))
# Fenetres de clips
CLIP_W, CLIP_Z0, CLIP_Z1 = 8.0, H - 8.0, H - 5.6
for x in CLIP_X:
    for side in "NS":
        cuts.append(wall_window(side, x - CLIP_W / 2 - 0.4, x + CLIP_W / 2 + 0.4, CLIP_Z0, CLIP_Z1, 0.4))
for c in cuts:
    base = base.cut(c)
base = base.removeSplitter()

# ---------------------------------------------------------------- couvercle
lid = rbox(OX0, OX1, OY0, OY1, H, H + LID, 3.0)
LIP_D, LIP_T = 2.5, 1.6
lip = rbox(X0 + FIT, X1 - FIT, Y0 + FIT, Y1 - FIT, H - LIP_D, H + 0.01, 1.0).cut(
    rbox(X0 + FIT + LIP_T, X1 - FIT - LIP_T, Y0 + FIT + LIP_T, Y1 - FIT - LIP_T, H - LIP_D - 1, H + 1, 0.5))
lip = lip.cut(box(ANTENNA_X - 2.0, ANTENNA_X + 2.0, Y1 - 3, Y1 + 1, H - LIP_D - 1, H + 1))
for x in CLIP_X:                                  # degagement levre autour des languettes
    for yw in (Y0, Y1):
        lip = lip.cut(box(x - CLIP_W / 2 - 1.0, x + CLIP_W / 2 + 1.0, yw - 3, yw + 3, H - LIP_D - 1, H + 1))
lid = lid.fuse(lip)

# Clips : languette 8 x 1.2 mm, longueur libre ~8 mm, bossage 0.6 mm a rampe.
CLIP_T, BUMP = 1.2, 0.6
for x in CLIP_X:
    for sgn, yw in ((-1, Y0), (1, Y1)):
        y_out = yw - sgn * FIT
        y_in = y_out - sgn * CLIP_T
        tab = box(x - CLIP_W / 2, x + CLIP_W / 2, min(y_in, y_out), max(y_in, y_out), CLIP_Z0 - 0.3, H + 0.01)
        zc, zb = CLIP_Z1 - 0.3, CLIP_Z0 - 0.1
        pts = [V(x - CLIP_W / 2, y_out - sgn * 0.01, zb), V(x - CLIP_W / 2, y_out + sgn * BUMP, zc),
               V(x - CLIP_W / 2, y_out - sgn * 0.01, zc), V(x - CLIP_W / 2, y_out - sgn * 0.01, zb)]
        tri = Part.Face(Part.makePolygon(pts)).extrude(V(CLIP_W, 0, 0))
        lid = lid.fuse(tab).fuse(tri)

lid_cuts = []
for (px, py), side in TERMINALS:                  # acces tournevis vis borniers
    lid_cuts.append(cyl(px, py, 2.4, H - 1, H + LID + 1))
for x, y in LEDS:                                 # fenetre LED
    lid_cuts.append(cyl(x, y, 2.0, H - 1, H + LID + 1))
# Ventilation haute : fentes 1.6 x 12 au-dessus des relais et de la zone centrale
for x in [46.0 + i * 3.2 for i in range(6)] + [-24.0 + i * 3.2 for i in range(8)]:
    lid_cuts.append(box(x - 0.8, x + 0.8, -6.0, 6.0, H - 1, H + LID + 1))
for c in lid_cuts:
    lid = lid.cut(c)
lid = lid.removeSplitter()

# ---------------------------------------------------------------- verification
doc = App.newDocument("pcb")
Import.insert(STEP_PATH, doc.Name)
root = [o for o in doc.Objects if o.TypeId == "App::Part" and not o.InList][0]
pcb_solids = []
for o in root.Group:
    if hasattr(o, "Shape") and not o.Shape.isNull():
        for s in o.Shape.Solids:
            s = s.copy()
            s.translate(V(0, 0, PCB_Z))
            pcb_solids.append((o.Label, s))

collisions = []
for name, part in (("fond", base), ("couvercle", lid)):
    for label, s in pcb_solids:
        if not part.BoundBox.intersect(s.BoundBox):
            continue
        v = part.common(s).Volume
        if v > 0.01:
            collisions.append({"piece": name, "composant": label, "volume_mm3": round(v, 3)})

checks = {}
for name, part in (("fond", base), ("couvercle", lid)):
    b = part.BoundBox
    checks[name] = {"valid": part.isValid(), "solids": len(part.Solids), "volume_mm3": round(part.Volume, 1),
                    "size_mm": [round(b.XLength, 2), round(b.YLength, 2), round(b.ZLength, 2)]}
checks["fond_couvercle_intersection_mm3"] = round(base.common(lid).Volume, 3)
checks["pcb_collisions"] = collisions
checks["hauteur_fond_mm"] = round(H, 2)


# ---------------------------------------------------------------- export
def stl(shape, path):
    m = MeshPart.meshFromShape(Shape=shape, LinearDeflection=0.03, AngularDeflection=0.2)
    m.write(path)
    return bool(m.isSolid() and not m.hasNonManifolds())


base.exportStep(os.path.join(HERE, "fond.step"))
lid.exportStep(os.path.join(HERE, "couvercle.step"))
checks["fond"]["stl_ok"] = stl(base, os.path.join(HERE, "fond.stl"))
lid_print = lid.copy()
lid_print.rotate(V(0, 0, 0), V(1, 0, 0), 180)     # face exterieure sur le plateau
lid_print.translate(V(0, 0, -lid_print.BoundBox.ZMin))
checks["couvercle"]["stl_ok"] = stl(lid_print, os.path.join(HERE, "couvercle.stl"))

asm = App.newDocument("asm")
for n, s in (("Fond", base), ("Couvercle", lid)):
    asm.addObject("Part::Feature", n).Shape = s
asm.addObject("Part::Feature", "PCB").Shape = Part.makeCompound([s for _, s in pcb_solids])
Import.export(asm.Objects, os.path.join(HERE, "assemblage.step"))

with open(os.path.join(HERE, "verification.json"), "w", encoding="utf-8") as f:
    json.dump(checks, f, indent=2, ensure_ascii=False)
print(json.dumps(checks, indent=2, ensure_ascii=False))
