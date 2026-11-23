package ancic.karim.onlyheat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleTest {
    // The controller's real /scheduler form (whitespace collapsed), captured 2026-11-21.
    private val page = """<html><body><form id="formSchedule"> <div class="card-body"> <small class="text-muted">Set a heating schedule for a specific room and for a specific time of day. Those settings will override the Smart Heating/Cooling and default heating/cooling functions. </small> <div class="schedule-container"> <div class="schedule-repater"> <hr> <div class="row"> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="entity_id[]"> <option value="0">- Room -</option> <option value="3" selected>Spavaca soba</option><option value="2" >Dnevni boravak/kuhinja</option><option value="1" >Kupaona</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="scheduleDay[]"> <option value="0" selected>Every Day</option><option value="Mon" >Monday</option><option value="Tue" >Tuesday</option><option value="Wed" >Wednesday</option><option value="Thu" >Thursday</option><option value="Fri" >Friday</option><option value="Sat" >Saturday</option><option value="Sun" >Sunday</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleStart[]" class="form-control schedule-time" placeholder="Start Hour" data-field="time" readonly value="06:00"> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleEnd[]" class="form-control schedule-time" placeholder="End Hour" data-field="time" readonly value="23:00"> </div> </div> <div class="col-sm-3 pb-3"> <button type="button" class="btn btn-primary-outline modal-temp-minus" style="width: 25%; float: left; border-radius: .25rem; border-top-left-radius: 1rem; border-bottom-left-radius: 1rem;"> <i class="fa fa-minus" aria-hidden="true"></i></button> <input type="text" name="scheduleTarget[]" class="form-control text-center modal-temp-input" value="23" readonly style="width: 50%; float: left;"> <button type="button" class="btn btn-primary-outline modal-temp-plus" style="width: 25%; float: left; border-radius: .25rem; border-top-right-radius: 1rem; border-bottom-right-radius: 1rem;"> <i class="fa fa-plus" aria-hidden="true"></i></button> </div> <div class="col-sm-1"> <button type="button" class="btn btn-primary-outline w-100 remove-schedule"> <i class="fa fa-trash"></i><span class="d-xs-block d-sm-none d-md-none d-lg-none"> Remove</span> </button> </div> </div> </div> <div class="schedule-repater"> <hr> <div class="row"> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="entity_id[]"> <option value="0">- Room -</option> <option value="3" >Spavaca soba</option><option value="2" selected>Dnevni boravak/kuhinja</option><option value="1" >Kupaona</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="scheduleDay[]"> <option value="0" selected>Every Day</option><option value="Mon" >Monday</option><option value="Tue" >Tuesday</option><option value="Wed" >Wednesday</option><option value="Thu" >Thursday</option><option value="Fri" >Friday</option><option value="Sat" >Saturday</option><option value="Sun" >Sunday</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleStart[]" class="form-control schedule-time" placeholder="Start Hour" data-field="time" readonly value="06:00"> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleEnd[]" class="form-control schedule-time" placeholder="End Hour" data-field="time" readonly value="23:00"> </div> </div> <div class="col-sm-3 pb-3"> <button type="button" class="btn btn-primary-outline modal-temp-minus" style="width: 25%; float: left; border-radius: .25rem; border-top-left-radius: 1rem; border-bottom-left-radius: 1rem;"> <i class="fa fa-minus" aria-hidden="true"></i></button> <input type="text" name="scheduleTarget[]" class="form-control text-center modal-temp-input" value="25" readonly style="width: 50%; float: left;"> <button type="button" class="btn btn-primary-outline modal-temp-plus" style="width: 25%; float: left; border-radius: .25rem; border-top-right-radius: 1rem; border-bottom-right-radius: 1rem;"> <i class="fa fa-plus" aria-hidden="true"></i></button> </div> <div class="col-sm-1"> <button type="button" class="btn btn-primary-outline w-100 remove-schedule"> <i class="fa fa-trash"></i><span class="d-xs-block d-sm-none d-md-none d-lg-none"> Remove</span> </button> </div> </div> </div> <div class="schedule-repater"> <hr> <div class="row"> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="entity_id[]"> <option value="0">- Room -</option> <option value="3" >Spavaca soba</option><option value="2" >Dnevni boravak/kuhinja</option><option value="1" selected>Kupaona</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <select class="form-control schedule-select" name="scheduleDay[]"> <option value="0" selected>Every Day</option><option value="Mon" >Monday</option><option value="Tue" >Tuesday</option><option value="Wed" >Wednesday</option><option value="Thu" >Thursday</option><option value="Fri" >Friday</option><option value="Sat" >Saturday</option><option value="Sun" >Sunday</option> </select> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleStart[]" class="form-control schedule-time" placeholder="Start Hour" data-field="time" readonly value="06:00"> </div> </div> <div class="col-sm-2"> <div class="form-group"> <input type="text" name="scheduleEnd[]" class="form-control schedule-time" placeholder="End Hour" data-field="time" readonly value="23:00"> </div> </div> <div class="col-sm-3 pb-3"> <button type="button" class="btn btn-primary-outline modal-temp-minus" style="width: 25%; float: left; border-radius: .25rem; border-top-left-radius: 1rem; border-bottom-left-radius: 1rem;"> <i class="fa fa-minus" aria-hidden="true"></i></button> <input type="text" name="scheduleTarget[]" class="form-control text-center modal-temp-input" value="22" readonly style="width: 50%; float: left;"> <button type="button" class="btn btn-primary-outline modal-temp-plus" style="width: 25%; float: left; border-radius: .25rem; border-top-right-radius: 1rem; border-bottom-right-radius: 1rem;"> <i class="fa fa-plus" aria-hidden="true"></i></button> </div> <div class="col-sm-1"> <button type="button" class="btn btn-primary-outline w-100 remove-schedule"> <i class="fa fa-trash"></i><span class="d-xs-block d-sm-none d-md-none d-lg-none"> Remove</span> </button> </div> </div> </div> </div> <hr> <div class="row mt-3"> <div class="col-sm-4"> <button type="button" class="btn btn-primary-outline w-50 add-schedule pointer" style=""><i class="fa fa-plus"></i> Add </button> </div> </div> </div> <div class="card-footer"> <button type="button" id="saveSchedule" class="btn btn-primary pointer"><i class="fa fa-calendar"></i> Save Schedule </button> </div> </form></body></html>"""

    @Test fun apiPinFromSetupPage() {
        // The controller's /setup markup, captured 2026-11-23 (PIN replaced).
        val setup = """<p class="text-center">PIN:</p>
						<h5 class="text-center" id="apiPin">123456</h5>
						<small class="text-muted">Used to login into the mobile application</small>"""
        assertEquals("123456", parseApiPin(setup))
        assertEquals(null, parseApiPin("<title>Dashboard | TermoTehnik PI</title>"))
    }

    @Test fun parseScheduleFromRealPage() {
        val s = parseSchedule(page)
        assertEquals(listOf("3" to "Spavaca soba", "2" to "Dnevni boravak/kuhinja", "1" to "Kupaona"), s.rooms)
        assertEquals(
            listOf(
                ScheduleEntry("3", "0", "06:00", "23:00", 23.0),
                ScheduleEntry("2", "0", "06:00", "23:00", 25.0),
                ScheduleEntry("1", "0", "06:00", "23:00", 22.0),
            ),
            s.entries,
        )
    }

    @Test fun blankRowIsNotAnEntry() {
        val blank = page.replace("<option value=\"3\" selected>", "<option value=\"3\" >")
            .replaceFirst("<option value=\"0\">- Room -</option>", "<option value=\"0\" selected>- Room -</option>")
        assertEquals(2, parseSchedule(blank).entries.size)
    }

    @Test fun truncatedPageStillParses() {
        assertEquals(parseSchedule(page).entries, parseSchedule(page.replace("</form>", "")).entries)
    }

    @Test fun interruptedSaveRefreshesBaselineAndKeepsRevertedDraft() = runTest {
        val original = parseSchedule(page)
        val editor = ScheduleModel()
        editor.showSchedule(original)
        assertFalse(editor.needsLoad)
        editor.entries[0] = editor.entries[0].copy(target = 24.0)

        val stored = CompletableDeferred<Schedule>()
        val save = launch {
            editor.save { sent ->
                stored.complete(original.copy(entries = sent))
                awaitCancellation()
            }
        }
        val controllerSchedule = stored.await()
        editor.entries[0] = original.entries[0]
        save.cancelAndJoin()

        assertTrue(editor.needsLoad)
        editor.showSchedule(controllerSchedule)
        assertFalse(editor.needsLoad)
        assertEquals(controllerSchedule.entries, editor.saved.value)
        assertEquals(original.entries, editor.entries.toList())
    }
}
