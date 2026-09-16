import json

path = r'D:\PCL2\.minecraft\versions\0.49备份\temp_clone\vehicle-lock-mod\src\main\resources\assets\vehiclelock\models\block\gas_pump.json'

with open(path, 'r', encoding='utf-8') as f:
    model = json.load(f)

# 缩放系数
SCALE_X = 1.3   # 宽度略微加宽到约 0.75 格
SCALE_Y = 32.0 / 14.2  # 高度拉到 32 单位 = 两格
SCALE_Z = 1.3
CX = 8.0
CZ = 8.0

def r(v):
    return round(v, 2)

for el in model['elements']:
    frm = el['from']
    to = el['to']
    el['from'] = [r(CX + (frm[0] - CX) * SCALE_X), r(frm[1] * SCALE_Y), r(CZ + (frm[2] - CZ) * SCALE_Z)]
    el['to']   = [r(CX + (to[0] - CX) * SCALE_X),   r(to[1] * SCALE_Y),   r(CZ + (to[2] - CZ) * SCALE_Z)]
    for face in el['faces'].values():
        if 'uv' in face:
            face['uv'] = [r(face['uv'][0] / 2), r(face['uv'][1] / 2), r(face['uv'][2] / 2), r(face['uv'][3] / 2)]

with open(path, 'w', encoding='utf-8') as f:
    json.dump(model, f, ensure_ascii=False, indent=2)

xs, ys, zs = [], [], []
for el in model['elements']:
    xs += [el['from'][0], el['to'][0]]
    ys += [el['from'][1], el['to'][1]]
    zs += [el['from'][2], el['to'][2]]
print('X range:', min(xs), '-', max(xs))
print('Y range:', min(ys), '-', max(ys))
print('Z range:', min(zs), '-', max(zs))
