package com.czqwq.talkwith.teams;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.czqwq.talkwith.TalkWith;

public class TeamDataRegistry {

    private static final Map<String, Supplier<ITeamData>> TEAM_DATA_REGISTRY = new HashMap<>();

    public static void register(String key, Supplier<ITeamData> factory) {
        if (TEAM_DATA_REGISTRY.containsKey(key)) {
            TalkWith.LOG.error("[TalkWithTeams] Attempted to register duplicate teamdata key: {}", key);
        } else {
            TEAM_DATA_REGISTRY.put(key, factory);
        }
    }

    public static ITeamData construct(String key) {
        Supplier<ITeamData> dataSupplier = TEAM_DATA_REGISTRY.get(key);
        if (dataSupplier != null) {
            return dataSupplier.get();
        }
        TalkWith.LOG.error("[TalkWithTeams] Teamdata key was not registered: {}", key);
        return null;
    }

    public static Set<String> getRegisteredKeys() {
        return Collections.unmodifiableSet(TEAM_DATA_REGISTRY.keySet());
    }
}
