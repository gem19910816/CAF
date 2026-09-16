# -*- coding: utf-8 -*-
"""Generate satchel / school_bag / hiking_backpack models + textures for CAF."""
import json, os, uuid, random
from PIL import Image

OUT = r"C:\Users\79662\Desktop\僵毁物品"
RES = r"D:\PCL2\.minecraft\versions\0.49备份\tarkov_stamina_mod\src\main\resources"
random.seed(20260912)

# ---------- textures (16x16, CAF apocalypse palette) ----------
def make_tex(name, base, vary=10, stain=None):
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            n = random.randint(-vary, vary)
            c = tuple(max(0, min(255, b + n)) for b in base) + (255,)
            px[x, y] = c
    if stain:  # a few darker grime patches
        for _ in range(stain):
            sx, sy = random.randint(0, 12), random.randint(0, 12)
            for dy in range(3):
                for dx in range(3):
                    x, y = sx + dx, sy + dy
                    r, g, b, a = px[x, y]
                    px[x, y] = (max(0, r - 30), max(0, g - 30), max(0, b - 30), a)
    img.save(os.path.join(OUT, name + ".png"))
    img.save(os.path.join(RES, "assets/caf/textures/item", name + ".png"))

make_tex("worn_canvas_green", (92, 96, 66), 12, stain=3)
make_tex("faded_cloth_red", (122, 70, 58), 12, stain=3)
make_tex("hiking_canvas_orange", (140, 88, 44), 12, stain=3)

# ---------- model builder ----------
def el(name, frm, to, tex, uv=None):
    """uv: [w,h] for box_uv or None -> use dims"""
    w = round(to[0]-frm[0], 2); h = round(to[1]-frm[1], 2); d = round(to[2]-frm[2], 2)
    uvw, uvh = (uv if uv else [max(w,d), max(h,d)])
    faces = {}
    for f in ("north","south","east","west","up","down"):
        faces[f] = {"uv": [0, 0, uvw, uvh], "texture": tex}
    return {"name": name, "box_uv": True, "rescale": False,
            "from": list(frm), "to": list(to), "uuid": uuid.uuid4().hex,
            "faces": faces}

DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, -35], "translation": [0, 1.25, -3.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, -90, 35], "translation": [0, 1.25, -3.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]}
}

def export(cn_name, file_id, textures, elements):
    # blockbench project
    bb = {
        "meta": {"format_version": "4.5", "model_format": "java_block", "box_uv": True},
        "name": cn_name, "model_identifier": file_id,
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": [{"name": cn_name, "origin": [8, 8, 8], "uuid": uuid.uuid4().hex,
                      "children": [e["uuid"] for e in elements]}],
        "textures": [{"name": t, "folder": "", "namespace": "caf",
                      "id": str(i), "path": t + ".png", "uuid": uuid.uuid4().hex,
                      "relative_path": t + ".png"}
                     for i, t in enumerate(textures)],
        "display": DISPLAY
    }
    with open(os.path.join(OUT, cn_name + ".bbmodel"), "w", encoding="utf-8") as f:
        json.dump(bb, f, ensure_ascii=False, indent=1)

    # minecraft item json
    mc = {
        "format_version": "1.9.0", "credit": "Made with Blockbench",
        "textures": {str(i): "caf:item/" + t for i, t in enumerate(textures)},
        "elements": [
            {"name": e["name"], "from": e["from"], "to": e["to"],
             "faces": {k: {"uv": v["uv"], "texture": "#" + str(v["texture"])}
                       for k, v in e["faces"].items()}}
            for e in elements
        ],
        "display": DISPLAY
    }
    with open(os.path.join(OUT, cn_name + ".json"), "w", encoding="utf-8") as f:
        json.dump(mc, f, ensure_ascii=False, indent=1)
    with open(os.path.join(RES, "assets/caf/models/item", file_id + ".json"), "w", encoding="utf-8") as f:
        json.dump(mc, f, ensure_ascii=False, indent=1)

# ---------- 挎包 satchel: small flat shoulder bag ----------
satchel = [
    el("main_body", (4, 3, 5.5), (12, 10, 10.5), 0, [8, 7]),      # dark_canvas
    el("front_flap", (3.8, 7.5, 4.6), (12.2, 11, 10.8), 1, [8.4, 3.5]),  # old_leather
    el("bottom_wear", (4.2, 2.4, 5.7), (11.8, 3.4, 10.3), 1, [7.6, 1]),
    el("side_gusset_left", (3.6, 3.4, 5.9), (4.2, 9.6, 10.1), 0, [4.2, 6.2]),
    el("side_gusset_right", (11.8, 3.4, 5.9), (12.4, 9.6, 10.1), 0, [4.2, 6.2]),
    el("buckle", (7.2, 6.2, 4.2), (8.8, 7.6, 4.8), 2, [1.6, 1.4]), # rusted_metal
    el("strap_front", (7.4, 10.9, 6), (8.6, 16.5, 7), 1, [1.2, 5.6]),  # shoulder strap up over
    el("strap_back", (7.4, 10.9, 9.4), (8.6, 16.5, 10.4), 1, [1.2, 5.6]),
    el("strap_top", (7.4, 15.9, 6), (8.6, 16.9, 10.4), 1, [1.2, 4.4]),
    el("small_patch", (5, 4.5, 4.55), (7, 6, 4.9), 2, [2, 1.5]),
]
export("挎包", "satchel", ["dark_canvas", "old_leather", "rusted_metal"], satchel)

# ---------- 书包 school_bag: boxy pack with front pocket ----------
school = [
    el("main_body", (3.5, 2, 5), (12.5, 12.5, 11), 0, [9, 10.5]),   # worn_canvas_green
    el("top_flap", (3.3, 11.3, 4.6), (12.7, 13.6, 11.4), 1, [9.4, 2.3]),  # faded_cloth_red accent
    el("front_pocket", (4.5, 3, 3.6), (11.5, 8.5, 5.4), 1, [7, 5.5]),
    el("front_pocket_flap", (4.3, 8.1, 3.3), (11.7, 9.9, 5.5), 1, [7.4, 1.8]),
    el("bottom_reinforce", (3.7, 1.2, 5.2), (12.3, 2.6, 11.2), 2, [8.6, 1.4]),  # old_leather
    el("left_strap", (4.2, 3.5, 10.9), (5.8, 12, 12.2), 2, [1.6, 8.5]),
    el("right_strap", (10.2, 3.5, 10.9), (11.8, 12, 12.2), 2, [1.6, 8.5]),
    el("handle_top", (6.4, 13.5, 7.4), (9.6, 14.5, 8.6), 2, [3.2, 1]),
    el("handle_left", (6.4, 12.6, 7.4), (7.2, 14.5, 8.6), 2, [0.8, 1.9]),
    el("handle_right", (8.8, 12.6, 7.4), (9.6, 14.5, 8.6), 2, [0.8, 1.9]),
    el("zipper_pull", (7.6, 8.4, 3.1), (8.4, 9.6, 3.6), 3, [0.8, 1.2]),  # rusted_metal
    el("name_tag", (5, 9.8, 3.35), (7.6, 11.2, 3.7), 3, [2.6, 1.4]),
    el("side_pocket", (12.3, 3.2, 6.4), (13.5, 7.2, 9.8), 0, [3.4, 4]),
]
export("书包", "school_bag", ["worn_canvas_green", "faded_cloth_red", "old_leather", "rusted_metal"], school)

# ---------- 登山包 hiking_backpack: tall pack with lid and bedroll ----------
hiking = [
    el("main_body", (3, 1.5, 5), (13, 13.5, 11.5), 0, [10, 12]),     # hiking_canvas_orange
    el("top_lid", (2.7, 12.6, 4.4), (13.3, 15.4, 12.2), 1, [10.6, 2.8]),  # worn_canvas
    el("lid_strap_left", (4.4, 15.3, 5), (5.6, 16.4, 11.6), 2, [1.2, 1.1]),  # old_leather
    el("lid_strap_right", (10.4, 15.3, 5), (11.6, 16.4, 11.6), 2, [1.2, 1.1]),
    el("front_pocket", (4.4, 3.4, 3.6), (11.6, 9, 5.4), 1, [7.2, 5.6]),
    el("front_pocket_flap", (4.2, 8.6, 3.3), (11.8, 10.6, 5.5), 1, [7.6, 2]),
    el("left_side_pocket", (1.6, 3, 6), (3.4, 8.4, 11), 1, [5, 5.4]),
    el("right_side_pocket", (12.6, 3, 6), (14.4, 8.4, 11), 1, [5, 5.4]),
    el("bedroll", (3.4, 15.6, 6), (12.6, 17.6, 10.8), 3, [9.2, 2]),  # dirty_blanket
    el("bedroll_strap_left", (4.8, 15.2, 5.6), (6, 18, 11.2), 2, [1.2, 2.8]),
    el("bedroll_strap_right", (10, 15.2, 5.6), (11.2, 18, 11.2), 2, [1.2, 2.8]),
    el("shoulder_strap_left", (4, 3.6, 11.2), (5.7, 12.8, 12.7), 2, [1.7, 9.2]),
    el("shoulder_strap_right", (10.3, 3.6, 11.2), (12, 12.8, 12.7), 2, [1.7, 9.2]),
    el("waist_belt_left", (2.6, 1.8, 10.8), (5.4, 3.4, 12.2), 2, [2.8, 1.6]),
    el("waist_belt_right", (10.6, 1.8, 10.8), (13.4, 3.4, 12.2), 2, [2.8, 1.6]),
    el("bottom_wear", (3.2, 0.8, 5.2), (12.8, 2.2, 11.7), 2, [9.6, 1.4]),
    el("rope_wrap_front", (3, 9.8, 4.2), (13, 10.4, 4.8), 4, [10, 0.6]),  # salvage_rope
    el("rope_wrap_back", (3, 9.8, 11.4), (13, 10.4, 12), 4, [10, 0.6]),
    el("buckle_left", (4.6, 6.4, 3.2), (6, 7.8, 3.8), 5, [1.4, 1.4]),  # rusted_metal
    el("buckle_right", (10, 6.4, 3.2), (11.4, 7.8, 3.8), 5, [1.4, 1.4]),
    el("carabiner", (12.9, 8.6, 5), (13.6, 10.4, 5.8), 5, [0.7, 1.8]),
]
export("登山包", "hiking_backpack",
       ["hiking_canvas_orange", "worn_canvas", "old_leather", "dirty_blanket", "salvage_rope", "rusted_metal"], hiking)

print("done")


# ---------- exact 16x16 inventory icons ----------
def icon(name, main, accent, strap):
    im = Image.new("RGBA", (16,16), (0,0,0,0))
    px = im.load()
    def rect(x0,y0,x1,y1,c):
        for yy in range(y0,y1+1):
            for xx in range(x0,x1+1):
                px[xx,yy]=c
    # body
    rect(3,5,12,14,main)
    # flap
    rect(2,3,13,6,accent)
    # straps
    rect(5,1,6,3,strap)
    rect(9,1,10,3,strap)
    # outline shading
    for y in range(5,15):
        px[3,y]=tuple(max(0,c-25) for c in main[:3])+(255,)
        px[12,y]=tuple(max(0,c-25) for c in main[:3])+(255,)
    for x in range(3,13):
        px[x,14]=tuple(max(0,c-25) for c in main[:3])+(255,)
    im.save(os.path.join(OUT, name + ".png"))
    im.save(os.path.join(RES, "assets/caf/textures/item", name + ".png"))

icon("satchel", (92,96,66,255), (140,88,44,255), (60,60,60,255))
icon("school_bag", (122,70,58,255), (60,60,60,255), (90,90,90,255))
icon("hiking_backpack", (140,88,44,255), (90,90,90,255), (50,50,50,255))
print("icons done")
