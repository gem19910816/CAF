import json

path = r'D:\PCL2\.minecraft\versions\0.49备份\temp_clone\vehicle-lock-mod\src\main\resources\assets\vehiclelock\models\block\gas_pump.json'

with open(path, 'r', encoding='utf-8') as f:
    model = json.load(f)

# 只居中，不缩放
xs, ys, zs = [], [], []
for el in model['elements']:
    xs += [el['from'][0], el['to'][0]]
    ys += [el['from'][1], el['to'][1]]
    zs += [el['from'][2], el['to'][2]]
minx, maxx = min(xs), max(xs)
miny, maxy = min(ys), max(ys)
minz, maxz = min(zs), max(zs)

cx = (minx + maxx) / 2.0
cz = (minz + maxz) / 2.0
dx = 8.0 - cx
dy = 0.0 - miny
dz = 8.0 - cz

for el in model['elements']:
    el['from'] = [round(el['from'][0] + dx, 2), round(el['from'][1] + dy, 2), round(el['from'][2] + dz, 2)]
    el['to']   = [round(el['to'][0] + dx, 2),   round(el['to'][1] + dy, 2),   round(el['to'][2] + dz, 2)]

with open(path, 'w', encoding='utf-8') as f:
    json.dump(model, f, ensure_ascii=False, indent=2)

print('shift: dx=%.2f dy=%.2f dz=%.2f' % (dx, dy, dz))
print('new bbox X: %.2f ~ %.2f (width %.2f)' % (minx+dx, maxx+dx, maxx-minx))
print('new bbox Y: %.2f ~ %.2f (height %.2f)' % (miny+dy, maxy+dy, maxy-miny))
print('new bbox Z: %.2f ~ %.2f (depth %.2f)' % (minz+dz, maxz+dz, maxz-minz))
