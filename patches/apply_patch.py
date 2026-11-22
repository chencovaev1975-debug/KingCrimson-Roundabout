from pathlib import Path

root = Path(".")
effect = root / "common/src/main/java/net/hydra/jojomod/client/ClientEffectUtil.java"
frag = root / "common/src/main/java/net/hydra/jojomod/event/TerrainFragments.java"
renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java"

s = effect.read_text(encoding="utf-8")
old = """                TerrainFragments fragment = new TerrainFragments();

                fragment.state = state;

                fragment.pos = Vec3.atCenterOf(pos).add(
                        random.nextDouble() * 0.4 - 0.2,
                        0.05,
                        random.nextDouble() * 0.4 - 0.2
                );

                fragment.prevPos = fragment.pos;

                fragment.velocity = new Vec3(
                        random.nextGaussian() * 0.01,
                        0.04 + random.nextDouble() * 0.02,
                        random.nextGaussian() * 0.01
                );

                fragment.rotX = random.nextFloat() * 360F;
                fragment.rotY = random.nextFloat() * 360F;
                fragment.rotZ = random.nextFloat() * 360F;

                fragment.prevRotX = fragment.rotX;
                fragment.prevRotY = fragment.rotY;
                fragment.prevRotZ = fragment.rotZ;

                fragment.rotSpeedX = random.nextFloat() * 4F - 2F;
                fragment.rotSpeedY = random.nextFloat() * 4F - 2F;
                fragment.rotSpeedZ = random.nextFloat() * 4F - 2F;

                fragment.prevScale = 1;
                fragment.scale = 1;

                fragment.age = 0;
                fragment.maxAge = 50 + random.nextInt(30);

                terrainFragments.add(fragment);
                return;"""
new = """                Vec3 blockCenter = Vec3.atCenterOf(pos);
                // Split one sampled block into 27 small, independently drifting cubic chips.
                for (int chipX = 0; chipX < 3; chipX++) {
                    for (int chipY = 0; chipY < 3; chipY++) {
                        for (int chipZ = 0; chipZ < 3; chipZ++) {
                            TerrainFragments fragment = new TerrainFragments();
                            fragment.state = state;
                            fragment.chipX = chipX;
                            fragment.chipY = chipY;
                            fragment.chipZ = chipZ;
                            fragment.pos = blockCenter;
                            fragment.prevPos = fragment.pos;

                            double dx = (chipX - 1) * 0.012 + random.nextGaussian() * 0.008;
                            double dy = 0.025 + (chipY - 1) * 0.008 + random.nextDouble() * 0.018;
                            double dz = (chipZ - 1) * 0.012 + random.nextGaussian() * 0.008;
                            fragment.velocity = new Vec3(dx, dy, dz);

                            fragment.rotX = random.nextFloat() * 360F;
                            fragment.rotY = random.nextFloat() * 360F;
                            fragment.rotZ = random.nextFloat() * 360F;
                            fragment.prevRotX = fragment.rotX;
                            fragment.prevRotY = fragment.rotY;
                            fragment.prevRotZ = fragment.rotZ;
                            fragment.rotSpeedX = random.nextFloat() * 7F - 3.5F;
                            fragment.rotSpeedY = random.nextFloat() * 7F - 3.5F;
                            fragment.rotSpeedZ = random.nextFloat() * 7F - 3.5F;
                            fragment.prevScale = 1;
                            fragment.scale = 1;
                            fragment.age = 0;
                            fragment.maxAge = 38 + random.nextInt(24);
                            terrainFragments.add(fragment);
                        }
                    }
                }
                return;"""
if old not in s:
    raise SystemExit("Could not locate expected terrain-fragment spawn block; source changed.")
effect.write_text(s.replace(old, new, 1), encoding="utf-8")

s = frag.read_text(encoding="utf-8")
old = "    public BlockState state;\n"
new = "    public BlockState state;\n    public int chipX;\n    public int chipY;\n    public int chipZ;\n"
if old not in s:
    raise SystemExit("Could not locate TerrainFragments state field.")
frag.write_text(s.replace(old, new, 1), encoding="utf-8")

s = renderer.read_text(encoding="utf-8")
old = """            // Center rotation
            poseStack.translate(-0.5, -0.5, -0.5);
            poseStack.scale(scale,scale,scale);

            dispatcher.renderSingleBlock("""
new = """            // Position a 1/3-sized chip in its original 3x3x3 block cell.
            poseStack.translate(
                    -0.5 + (frag.chipX + 0.5) / 3.0,
                    -0.5 + (frag.chipY + 0.5) / 3.0,
                    -0.5 + (frag.chipZ + 0.5) / 3.0
            );
            poseStack.scale(scale / 3.0F, scale / 3.0F, scale / 3.0F);
            poseStack.translate(-0.5, -0.5, -0.5);

            dispatcher.renderSingleBlock("""
if old not in s:
    raise SystemExit("Could not locate terrain-fragment render transform.")
renderer.write_text(s.replace(old, new, 1), encoding="utf-8")
print("Applied: terrain blocks now emit 27 small cubic chips per sampled block.")
