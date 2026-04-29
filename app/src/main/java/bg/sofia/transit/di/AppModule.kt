package bg.sofia.transit.di

import android.content.Context
import bg.sofia.transit.data.db.TransitDatabase
import bg.sofia.transit.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TransitDatabase =
        TransitDatabase.getInstance(ctx)

    @Provides fun provideStopDao(db: TransitDatabase): StopDao     = db.stopDao()
    @Provides fun provideRouteDao(db: TransitDatabase): RouteDao   = db.routeDao()
    @Provides fun provideTripDao(db: TransitDatabase): TripDao     = db.tripDao()
    @Provides fun provideStopTimeDao(db: TransitDatabase): StopTimeDao = db.stopTimeDao()
    @Provides fun provideCalendarDateDao(db: TransitDatabase): CalendarDateDao = db.calendarDateDao()
}
