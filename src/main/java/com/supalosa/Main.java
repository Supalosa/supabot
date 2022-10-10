package com.supalosa;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.*;
import com.supalosa.bot.SupaBot;

import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) {
        SupaBot supaBot = new SupaBot(true);
        PlayerSettings opponent = S2Coordinator.createComputer(Race.TERRAN, Difficulty.HARD);
        LauncherUtils.startSC2(
                args,
                supaBot,
                LocalMap.of(Paths.get("HardwireAIE.SC2Map")),
                //BattlenetMap.of("BerlingradAIE"),
                false,
                new PlayerSettings[]{opponent});
    }
}