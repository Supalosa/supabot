package com.supalosa;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.BattlenetMap;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.Race;
import com.supalosa.bot.SupaBot;

public class Main {

    public static void main(String[] args) {
        SupaBot supaBot = new SupaBot();
        PlayerSettings opponent = S2Coordinator.createComputer(Race.TERRAN, Difficulty.HARD);
        LauncherUtils.startSC2(
                args,
                supaBot,
                BattlenetMap.of("Cloud Kingdom LE"),
                false,
                new PlayerSettings[]{opponent});
    }
}