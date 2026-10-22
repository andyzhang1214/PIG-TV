package com.pigtv.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.pigtv.cache.Cache;
import com.pigtv.cache.CacheDao;
import com.pigtv.cache.SearchDao;
import com.pigtv.cache.SearchHistory;
import com.pigtv.cache.StorageDrive;
import com.pigtv.cache.StorageDriveDao;
import com.pigtv.cache.VodCollect;
import com.pigtv.cache.VodCollectDao;
import com.pigtv.cache.VodRecord;
import com.pigtv.cache.VodRecordDao;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Database(entities = {Cache.class, VodRecord.class, VodCollect.class, StorageDrive.class, SearchHistory.class}, version = 3)
public abstract class AppDataBase extends RoomDatabase {
    public abstract CacheDao getCacheDao();

    public abstract VodRecordDao getVodRecordDao();

    public abstract VodCollectDao getVodCollectDao();

    public abstract StorageDriveDao getStorageDriveDao();

    public abstract SearchDao getSearchDao();
}
