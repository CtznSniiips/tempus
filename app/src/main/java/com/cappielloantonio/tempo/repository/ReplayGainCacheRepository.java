package com.cappielloantonio.tempo.repository;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.ReplayGainCacheDao;
import com.cappielloantonio.tempo.model.ReplayGainCache;

import java.util.Collections;
import java.util.List;

public class ReplayGainCacheRepository {
    private final ReplayGainCacheDao replayGainCacheDao = AppDatabase.getInstance().replayGainCacheDao();

    public ReplayGainCache getOne(String mediaId) {
        GetOneThreadSafe getOne = new GetOneThreadSafe(replayGainCacheDao, mediaId);
        Thread thread = new Thread(getOne);
        thread.start();
        try {
            thread.join();
            return getOne.getResult();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<ReplayGainCache> getMany(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return Collections.emptyList();

        GetManyThreadSafe getMany = new GetManyThreadSafe(replayGainCacheDao, mediaIds);
        Thread thread = new Thread(getMany);
        thread.start();
        try {
            thread.join();
            return getMany.getResult();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public void insert(ReplayGainCache replayGainCache) {
        if (replayGainCache == null) return;
        InsertThreadSafe insert = new InsertThreadSafe(replayGainCacheDao, replayGainCache);
        Thread thread = new Thread(insert);
        thread.start();
    }

    private static class GetOneThreadSafe implements Runnable {
        private final ReplayGainCacheDao dao;
        private final String mediaId;
        private ReplayGainCache result;

        private GetOneThreadSafe(ReplayGainCacheDao dao, String mediaId) {
            this.dao = dao;
            this.mediaId = mediaId;
        }

        @Override
        public void run() {
            result = dao.getOne(mediaId);
        }

        private ReplayGainCache getResult() {
            return result;
        }
    }

    private static class GetManyThreadSafe implements Runnable {
        private final ReplayGainCacheDao dao;
        private final List<String> mediaIds;
        private List<ReplayGainCache> result = Collections.emptyList();

        private GetManyThreadSafe(ReplayGainCacheDao dao, List<String> mediaIds) {
            this.dao = dao;
            this.mediaIds = mediaIds;
        }

        @Override
        public void run() {
            result = dao.getMany(mediaIds);
        }

        private List<ReplayGainCache> getResult() {
            return result;
        }
    }

    private static class InsertThreadSafe implements Runnable {
        private final ReplayGainCacheDao dao;
        private final ReplayGainCache replayGainCache;

        private InsertThreadSafe(ReplayGainCacheDao dao, ReplayGainCache replayGainCache) {
            this.dao = dao;
            this.replayGainCache = replayGainCache;
        }

        @Override
        public void run() {
            dao.insert(replayGainCache);
        }
    }
}
