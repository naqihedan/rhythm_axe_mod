package name.rhythm_axe_mod;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

public class ModGameRules {
    public static final GameRule<Boolean> CONFIRM_COMMAND =
        GameRuleBuilder.forBoolean(true)
            .category(GameRuleCategory.MISC)
            .buildAndRegister(Identifier.parse("rhythm_axe_mod:confirm_command"));
}
