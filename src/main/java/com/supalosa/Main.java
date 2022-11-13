package com.supalosa;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.*;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.debug.JFrameDebugTarget;

import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) {
        SupaBot supaBot = new SupaBot(true, new JFrameDebugTarget());
        PlayerSettings opponent = S2Coordinator.createComputer(Race.ZERG, Difficulty.VERY_HARD, AiBuild.MACRO);
        LauncherUtils.startSC2(
                args,
                supaBot,
                LocalMap.of(Paths.get("2000AtmospheresAIE.SC2Map")),
                false,
                new PlayerSettings[]{opponent});
    }
}