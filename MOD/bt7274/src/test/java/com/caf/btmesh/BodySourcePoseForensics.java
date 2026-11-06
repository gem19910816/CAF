package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Raw source-joint observations only: no cube retarget, grip correction, or terrain IK. */
public final class BodySourcePoseForensics {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),assets=root.resolve("mod_mesh/src/main/resources/assets/bt7274");
        var model=GltfSkin.load(Files.readAllBytes(assets.resolve("models/native/body.glb")));
        var meta=JsonParser.parseString(Files.readString(assets.resolve("motion/body.json"))).getAsJsonObject();
        var player=new NativeMotionPlayer(model,meta);var pose=model.createPose();
        var names=new HashMap<String,Integer>();for(int i=0;i<model.nodes.length;i++)names.put(model.nodes[i].name,i);
        var params=Map.of("aim_yaw",0f,"aim_pitch",0f);var output=new LinkedHashMap<String,Object>();
        var states=new LinkedHashMap<String,String>();states.put("base","bt_combat_idle");states.put("sp_generic","bt_reload_additive");states.put("mp_xo16","bt_reload_xo16_manual");states.put("vortex","bt_vortex_arm_pose_add");states.put("run","bt_combat_run_forward");states.put("sprint","bt_combat_sprint_forward_noaim");states.put("embark","at_MP_embark");states.put("dismount","at_dismount_stand");states.put("melee","at_player_melee_punch_01");
        states.put("quickstand","at_hotdrop_quickstand");
        for(var entry:states.entrySet()) {
            String label=entry.getKey(),sequence=entry.getValue();double duration=player.sequenceDuration(sequence,params);
            var samples=new ArrayList<Object>();
            for(int frame=0;frame<=30;frame++) {
                double cycle=frame/30.,seconds=cycle*duration;
                if(label.equals("sp_generic")||label.equals("mp_xo16")||label.equals("vortex"))player.sampleLayered("bt_combat_idle",0,sequence,seconds,0,1,params,pose);
                else player.sample(sequence,seconds,params,pose);
                var points=new LinkedHashMap<String,Object>();
                for(JsonElement rawBone:meta.getAsJsonArray("bones")) {
                    String bone=rawBone.getAsJsonObject().get("name").getAsString();
                    int n=names.get(bone),parent=model.nodes[n].parent;Matrix4f local=new Matrix4f(pose.world[n]);if(parent>=0)local.set(pose.world[parent]).invert().mul(pose.world[n]);
                    Vector3f p=pose.world[n].getTranslation(new Vector3f()),t=local.getTranslation(new Vector3f());Quaternionf q=local.getUnnormalizedRotation(new Quaternionf()).normalize();
                    points.put(bone,Map.of("world",new float[]{p.x,p.y,p.z},"world_matrix_column_major",pose.world[n].get(new float[16]),"local_translation",new float[]{t.x,t.y,t.z},"local_rotation",new float[]{q.x,q.y,q.z,q.w}));
                }
                samples.add(Map.of("cycle",cycle,"source_seconds",seconds,"bones",points));
            }
            output.put(label,Map.of("sequence",sequence,"duration",duration,"samples",samples));
        }
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(output));
        System.out.println("Raw source observations saved; no visual acceptance claimed.");
    }
}
