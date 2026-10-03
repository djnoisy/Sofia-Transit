package bg.sofia.transit.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
class JourneyService {
    sealed class TrackingState {
        object Idle : TrackingState()
        class Tracking : TrackingState()
    }
    companion object {
        val _trackingState = MutableStateFlow<TrackingState>(TrackingState.Idle)
        val trackingState: StateFlow<TrackingState> = _trackingState
    }
}
