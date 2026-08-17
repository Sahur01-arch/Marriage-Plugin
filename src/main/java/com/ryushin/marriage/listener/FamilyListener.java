package com.ryushin.marriage.listener;

import com.ryushin.marriage.command.FamilyCommand;
import com.ryushin.marriage.command.MarryCommand;
import org.bukkit.entity.Player;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class FamilyListener implements Listener {

    private final FamilyCommand familyCommand;
    private final MarryCommand marryCommand;

    public FamilyListener(
            FamilyCommand familyCommand,
            MarryCommand marryCommand
    ) {
        this.familyCommand = familyCommand;
        this.marryCommand = marryCommand;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        familyCommand.clearAdoptionsFor(uuid);
        marryCommand.clearProposalsFor(uuid);
    }
}
