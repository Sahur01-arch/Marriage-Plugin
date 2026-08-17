package com.ryushin.marriage.data;

import java.util.UUID;

public class FamilyRelation {

    private int id;
    private UUID playerUuid;
    private UUID relatedUuid;
    private RelationType relationType;

    // Constructor buat data BARU
    public FamilyRelation(UUID playerUuid, UUID relatedUuid, RelationType relationType) {
        this.playerUuid = playerUuid;
        this.relatedUuid = relatedUuid;
        this.relationType = relationType;
    }

    // Constructor buat data dari DATABASE (sudah ada id)
    public FamilyRelation(int id, UUID playerUuid, UUID relatedUuid, RelationType relationType) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.relatedUuid = relatedUuid;
        this.relationType = relationType;
    }

    public int getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public UUID getRelatedUuid() { return relatedUuid; }
    public RelationType getRelationType() { return relationType; }
}
