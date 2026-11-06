package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3f;

/** Geometric contact diagnostic after actual Java source evaluation/retarget.
 * Uses authored cube corners and face axes, never original model geometry. */
public final class CubeContactAudit {
    private record Box(Vector3f[] points,List<Vector3f> axes){}
    private static Box box(GltfSkin.Primitive p,GltfSkin.Pose pose){
        int count=p.positions().length/3;Vector3f[] points=new Vector3f[count];List<Vector3f> axes=new ArrayList<>();
        for(int i=0;i<count;i++){
            int j=p.joints()[i*4];points[i]=new Vector3f(p.positions()[i*3],p.positions()[i*3+1],p.positions()[i*3+2]);
            pose.joints[p.skin()][j].transformPosition(points[i]);Vector3f axis=new Vector3f(p.normals()[i*3],p.normals()[i*3+1],p.normals()[i*3+2]);
            pose.normals[p.skin()][j].transform(axis).normalize();if(axes.stream().allMatch(v->Math.abs(v.dot(axis))<.9999f))axes.add(axis);
        }return new Box(points,axes);
    }
    private static Map<String,Object> contact(Box a,Box b){
        List<Vector3f> axes=new ArrayList<>(a.axes);axes.addAll(b.axes);
        for(Vector3f x:a.axes)for(Vector3f y:b.axes){var cross=new Vector3f(x).cross(y);if(cross.lengthSquared()>1e-10)axes.add(cross.normalize());}
        float separation=0,penetration=Float.POSITIVE_INFINITY;
        for(Vector3f axis:axes){float amin=Float.POSITIVE_INFINITY,amax=Float.NEGATIVE_INFINITY,bmin=amin,bmax=amax;
            for(Vector3f p:a.points){float dot=p.dot(axis);amin=Math.min(amin,dot);amax=Math.max(amax,dot);}for(Vector3f p:b.points){float dot=p.dot(axis);bmin=Math.min(bmin,dot);bmax=Math.max(bmax,dot);}
            separation=Math.max(separation,Math.max(amin-bmax,bmin-amax));penetration=Math.min(penetration,Math.min(amax-bmin,bmax-amin));
        }return Map.of("intersects_or_touches",separation<=1e-5,"separating_axis_gap_metres",separation,"minimum_axis_penetration_metres",penetration);
    }
    public static void main(String[] args)throws Exception{
        var model=GltfSkin.load(Files.readAllBytes(Path.of(args[0])));var metadata=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        var provenance=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();var surfaces=provenance.getAsJsonArray("surfaces");
        var motion=new NativeMotionPlayer(model,metadata);var adapter=new CubePoseAdapter(model,metadata.getAsJsonObject("cube_rig"));var pose=model.createPose();
        Map<String,Integer> names=new HashMap<>();for(int i=0;i<surfaces.size();i++)names.put(surfaces.get(i).getAsJsonObject().get("author_name").getAsString(),i);
        Map<String,Object> states=new LinkedHashMap<>();
        for(String sequence:List.of("bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","at_Crouch_Aim")){
            motion.sample(sequence,.35,Map.of("aim_yaw",0f,"aim_pitch",0f),pose);adapter.apply(pose);adapter.alignWeaponToRightGrip(pose,1);
            Map<String,Object> pairs=new LinkedHashMap<>();Box grip=box(model.primitives.get(names.get("xo_grip")),pose);
            for(String name:List.of("right_palm","right_finger_pad_1","right_finger_pad_2","right_thumb_distal"))
                pairs.put(name+"_vs_grip",contact(box(model.primitives.get(names.get(name)),pose),grip));
            pairs.put("left_palm_vs_barrel_shroud",contact(box(model.primitives.get(names.get("left_palm")),pose),box(model.primitives.get(names.get("xo_barrel_shroud")),pose)));
            Map<String,Object> joints=new LinkedHashMap<>();for(int i=0;i<model.nodes.length;i++)if(List.of("def_r_wrist","def_l_wrist","def_r_finMidA","def_r_finMidC","def_l_finMidA","def_l_finMidC","ja_c_propGun","cube_hand_right","cube_hand_left","cube_weapon").contains(model.nodes[i].name)){var p=pose.world[i].getTranslation(new Vector3f());joints.put(model.nodes[i].name,List.of(p.x,p.y,p.z));}pairs.put("joint_positions_metres",joints);
            states.put(sequence,pairs);
            if(sequence.equals("bt_combat_idle")){
                List<Object> posed=new ArrayList<>();for(var primitive:model.primitives){List<Object> pts=new ArrayList<>();for(Vector3f p:box(primitive,pose).points)pts.add(List.of(p.x,p.y,p.z));posed.add(pts);}
                Files.writeString(Path.of(args[3]).resolveSibling("r3_body_combat_idle_vertices.json"),new Gson().toJson(posed));
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("states",states);result.put("source_mesh_vertices",0);result.put("scope","OBB separation after original source pose and current authored cube angular retarget; diagnostic, not visual acceptance");
        Files.writeString(Path.of(args[3]),new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
