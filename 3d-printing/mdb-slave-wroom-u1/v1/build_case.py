"""Boitier WROOM-U1 — CadQuery 2.x. Units: mm.
Run: python build_case.py [output_dir]
Optional original STEP beside script: wroom-u1.step (for assembly checks).
PCB datum is bottom face at z=0 in original model; installed at PCB_Z.
"""
from pathlib import Path
import sys, json
import cadquery as cq

OUT=Path(sys.argv[1] if len(sys.argv)>1 else 'output');OUT.mkdir(parents=True,exist_ok=True)
L,W,H=170.0,48.0,31.0
WALL,FLOOR,LID=2.4,2.4,2.4
PCB_Z=9.0
INSERT_OD,INSERT_LENGTH=3.5,5.0
INSERT_BORE=3.2 # Nominal pilot; validate on included test coupon.
INSERT_DEPTH=5.6
PCB_HOLES=[(-71.414936,-15.75),(-71.414936,15.75),(41.4757,-15.7906),(41.4857,14.7496)]
LID_HOLES=[(x,y) for x in (-79.0,79.0) for y in (-18.0,18.0)]

def box(x0,x1,y0,y1,z0,z1):
 return cq.Workplane('XY').box(x1-x0,y1-y0,z1-z0,centered=(False,False,False)).translate((x0,y0,z0))
def cylinder(x,y,r,z,h):
 return cq.Workplane('XY').workplane(offset=z).center(x,y).circle(r).extrude(h)
def rounded(l,w,h,r,z=0):
 return cq.Workplane('XY').box(l,w,h,centered=(True,True,False)).edges('|Z').fillet(r).translate((0,0,z))
def insert_cut(shape,x,y,top,diam=INSERT_BORE):
 shape=shape.cut(cylinder(x,y,diam/2,top-INSERT_DEPTH,INSERT_DEPTH+0.1))
 # 0.35 mm conical lead-in to help align the hot insert.
 lead=cq.Solid.makeCone(diam/2,1.9,0.4,cq.Vector(x,y,top-0.4))
 return shape.cut(lead)

base=rounded(L,W,H,3).cut(rounded(L-2*WALL,W-2*WALL,H,1.2,FLOOR))
for x,y in PCB_HOLES:
 base=base.union(cylinder(x,y,3.2,FLOOR-0.1,PCB_Z-FLOOR+0.1))
 base=insert_cut(base,x,y,PCB_Z)
for x,y in LID_HOLES:
 base=base.union(cylinder(x,y,3.8,FLOOR-0.1,H-FLOOR+0.1))
 # short transverse connection to end wall
 if x<0: base=base.union(box(-83,-79,y-3.2,y+3.2,FLOOR,H))
 else: base=base.union(box(79,83,y-3.2,y+3.2,FLOOR,H))
 base=insert_cut(base,x,y,H)

# Mounting ears exclusively on short left/right ends.
for sign in (-1,1):
 ear=rounded(22,30,4,3).translate((sign*93,0,0))
 slot=cq.Workplane('XY').center(sign*95,0).slot2D(10,4.5,0).extrude(5)
 ear=ear.cut(slot)
 for y in (-10,10):
  ear=ear.cut(box(sign*93-4.5,sign*93+4.5,y-1.5,y+1.5,-1,5))
 # Underside tunnel links tie slots, so a tie can pass behind a flush-mounted ear.
 ear=ear.cut(box(sign*93-4.7,sign*93+4.7,-11.5,11.5,-0.1,1.8))
 base=base.union(ear)

# Cable access cutouts open to upper rim; board is retained by four screws.
base=base.cut(box(-62,4,-25,-20.8,10.2,H+1))
base=base.cut(box(8.5,26,-25,-20.8,10.2,H+1))
base=base.cut(box(-42,39,20.8,25,10.2,H+1))
base=base.cut(box(82,86,-17.3,17.1,10.2,H+1))
# External antenna cable: U-shaped notch, 2.2 mm wide / deep under lid.
base=base.cut(box(-56.1,-53.9,20.6,25,H-2.2,H+1))

lid=rounded(L,W,LID,3,H)
# Windows over terminals / vertical headers; continuous central spine retained.
windows=[(-62,4,-22,-7.6),(8.5,26,-22,2.0),(-42,5.8,11.3,22),(5.8,39,7.6,22),(63.5,76,-17.6,17.4)]
for x0,x1,y0,y1 in windows: lid=lid.cut(box(x0,x1,y0,y1,H-1,H+LID+1))
for x,y in LID_HOLES: lid=lid.cut(cylinder(x,y,1.45,H-0.1,LID+0.2))
# Four small light / button access holes at low electronics region are deliberately
# omitted: the removable lid is the service access, no uncertain actuator geometry.

# Insert test coupon, with four blind bores. Print before committing full base.
coupon=rounded(46,14,8,2)
for x,diam in zip((-16.5,-5.5,5.5,16.5),(3.1,3.2,3.3,3.4)):
 coupon=insert_cut(coupon,x,0,8,diam)
 try:
  label=cq.Workplane('XY').workplane(offset=8).center(x,4.4).text(str(diam),2.6,0.35,font='DejaVu Sans',combine=True)
  coupon=coupon.union(label)
 except Exception: pass

parts={'fond':base,'couvercle':lid,'test_inserts':coupon}
checks={}
for name,part in parts.items():
 shape=part.val(); b=shape.BoundingBox()
 checks[name]={'valid':shape.isValid(),'solids':len(shape.Solids()),'volume_mm3':shape.Volume(),'size_mm':[b.xlen,b.ylen,b.zlen]}
 assert shape.isValid() and len(shape.Solids())==1,(name,checks[name])
 cq.exporters.export(part,str(OUT/(name+'.step')))
 # Planar lid: either face can lie on bed; STL starts at zero.
 printable=part.translate((0,0,-H)) if name=='couvercle' else part
 cq.exporters.export(printable,str(OUT/(name+'.stl')),tolerance=0.04,angularTolerance=0.12)
 shape.exportBrep(str(OUT/(name+'.brep')))
assembly=cq.Assembly(name='Boitier_WROOM_U1')
assembly.add(base,name='Fond',color=cq.Color(0.20,0.27,0.34))
assembly.add(lid,name='Couvercle',color=cq.Color(0.65,0.76,0.81))
assembly.save(str(OUT/'boitier_assemble.step'))
checks['base_lid_intersection_mm3']=base.val().intersect(lid.val()).Volume()
(OUT/'verification.json').write_text(json.dumps(checks,indent=2))
print(json.dumps(checks,indent=2),flush=True)
