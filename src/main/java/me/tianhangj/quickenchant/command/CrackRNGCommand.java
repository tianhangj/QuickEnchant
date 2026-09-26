package me.tianhangj.quickenchant.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.features.CCrackRng;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public class CrackRNGCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("qecrackrng")
            .executes(ctx -> crackPlayerRNG(ctx.getSource())));
    }

    private static int crackPlayerRNG(FabricClientCommandSource source) throws CommandSyntaxException {
        CCrackRng.crack(seed -> {
            source.sendFeedback(Component.translatable("commands.ccrackrng.success", Long.toHexString(seed)));
            PlayerRandCracker.setSeed(seed);
            Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        });
        return Command.SINGLE_SUCCESS;
    }

}
