import dev.baseio.security.Capillary
import kotlin.test.Test

class TestCapillary {
  @Test
  fun test(){
    with(Capillary("anmol")){
      initialize()
    }
  }
}