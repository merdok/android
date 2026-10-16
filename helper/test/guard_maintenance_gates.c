/* Guard DB phase, identity and custody gates, one arrangement per gate (split from
 * guard_maintenance_boundary.c, which keeps the full capture-to-retirement flows). */
#include "guard_boundary_harness.h"

/* ---------------------------------------------------------------------------------------------
 * Phase, identity and custody gates.  Each arrangement below makes every other independent check
 * pass, so the reply and the durable status can only come from the gate under test.
 * ------------------------------------------------------------------------------------------- */

static const char FLOW_A_PAYLOAD[] = "flow-installed-a";
static const char FLOW_B_PAYLOAD[] = "flow-candidate-b";
#define FLOW_ROWS \
    "636F6E666967|616C706861|74657874|V6F6E65|1\n" \
    "6F74686572|62657461|74657874|N|2\n"

static void flow_database(unsigned char database[128], unsigned schema) {
    memset(database, 0, 128);
    memcpy(database, "SQLite format 3", 15);
    database[63] = (unsigned char)schema;
}

static void flow_sqlite_answer(unsigned schema, int premigrate_probe) {
    char answer[256];
    snprintf(answer, sizeof answer, "%u\nok\n%d\n2\n" FLOW_ROWS, schema, premigrate_probe);
    sysexec_stub_clear_popen_rules();
    sysexec_stub_add_popen("PRAGMA query_only=ON", answer, 0);
}

/* WAIT_B_HEALTH at generation 9: exact B installed, B's checkpointed v15 database live and the
 * sqlite proof answering v15, so an exact B health report would succeed. */
static void reach_wait_b_health(char a_sha[65], char b_sha[65]) {
    setup();
    unsigned char database[128];
    flow_database(database, 14);
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    hash_bytes(FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, a_sha);
    hash_bytes(FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof FLOW_A_PAYLOAD - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof FLOW_B_PAYLOAD - 1, 3);
    stream_role("A", 3, a_sha, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 4);
    stream_role("B", 4, b_sha, FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 0600);
    guard_test_set_supervised(1);
    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 7 PREPARED\n") == 0,
        "gate fixture captures baseline (got %s)\n", reply);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "gate fixture starts exact B\n");
    write_file_mode(INSTALLED_APK, FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "gate fixture launches B\n");
    flow_database(database, 15);
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    flow_sqlite_answer(15, 1);
    guard_test_set_supervised(1);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 9 WAIT_B_HEALTH ") != NULL,
        "gate fixture reaches WAIT_B_HEALTH (got %s)\n", reply);
}

/* WAIT_A_REFUSAL at generation 12: B proven healthy, premigrate withheld, exact B still installed. */
static void reach_wait_a_refusal(char a_sha[65], char b_sha[65]) {
    reach_wait_b_health(a_sha, b_sha);
    char command[1024], reply[1024];
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 OK 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 B_HEALTHY\n") == 0,
        "gate fixture proves B healthy (got %s)\n", reply);
    unsigned char premigrate[128];
    flow_database(premigrate, 14);
    write_file_mode(DB_PATH ".v14.premigrate", premigrate, sizeof premigrate, 0600);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command, "GUARDACTION %s 10 WITHHOLD_PREMIGRATE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 11 RECOVERY_WITHHELD\n") == 0,
        "gate fixture withholds premigrate (got %s)\n", reply);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_LAUNCH_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "gate fixture relaunches B for the refusal attempt\n");
    guard_test_set_supervised(1);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 12 WAIT_A_REFUSAL ") != NULL,
        "gate fixture reaches WAIT_A_REFUSAL (got %s)\n", reply);
}

/* WAIT_A_HEALTH at generation 16, with A's durable RESTORED result in place and the sqlite proof
 * answering v14, so everything FINALIZE checks after the phase would already hold. */
static void reach_wait_a_health(char a_sha[65], char b_sha[65]) {
    reach_wait_a_refusal(a_sha, b_sha);
    char command[1024], reply[1024];
    snprintf(command, sizeof command,
        "GUARDREFUSAL %s 12 %s A %s 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE",
        SESSION, boot_nonce, a_sha);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDREFUSAL 13 A_REFUSED\n") == 0,
        "gate fixture records the A refusal (got %s)\n", reply);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command, "GUARDACTION %s 13 RESTORE_PREMIGRATE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 14 RECOVERY_RESTORED\n") == 0,
        "gate fixture restores premigrate (got %s)\n", reply);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_INSTALL_A &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "gate fixture starts exact A\n");
    write_file_mode(INSTALLED_APK, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_A, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_A &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
        "gate fixture launches A\n");
    unsigned char premigrate[128], b_primary[128];
    flow_database(premigrate, 14);
    flow_database(b_primary, 15);
    write_file_mode(DB_PATH, premigrate, sizeof premigrate, 0600);
    write_file_mode(DB_PATH ".v15.superseded", b_primary, sizeof b_primary, 0600);
    char b_primary_sha[65], premigrate_sha[65];
    hash_bytes(b_primary, sizeof b_primary, b_primary_sha);
    hash_bytes(premigrate, sizeof premigrate, premigrate_sha);
    write_restore_receipt("RESTORED", 15, sizeof b_primary, b_primary_sha,
        14, sizeof premigrate, premigrate_sha, 16);
    flow_sqlite_answer(14, 0);
    guard_test_set_supervised(1);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 16 WAIT_A_HEALTH ") != NULL,
        "gate fixture reaches WAIT_A_HEALTH (got %s)\n", reply);
}

/* ROLLBACK_DB_PREPARED at generation 10, reached by a determinate PM rejection of B with exact A
 * still installed: the next tick restores the baseline over the live primary. */
static void reach_rollback_db_prepared(char a_sha[65], char b_sha[65]) {
    const char *b_payload;
    size_t b_bytes;
    pid_t executor = -1;
    reach_submitted_b_for_pm_result(a_sha, b_sha, &b_payload, &b_bytes, &executor);
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 1 << 8) == GUARD_WORK_NONE,
        "restore fixture records the PM rejection\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "restore fixture consumes rollback with exact A installed\n");
    char reply[1024];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 10 ROLLBACK_DB_PREPARED ") != NULL,
        "restore fixture reaches ROLLBACK_DB_PREPARED (got %s)\n", reply);
}

static void test_health_rejects_stale_and_foreign_reports(void) {
    char a_sha[65], b_sha[65], command[1024], stale[256], foreign[256], status[1024];
    reach_wait_b_health(a_sha, b_sha);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 8 %s B %s 569 15 OK 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, stale, sizeof stale);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 OK 2 %s %s PRESENT NA",
        SIGNER, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, foreign, sizeof foreign);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(stale, "ERR STALE health\n") == 0 &&
          strcmp(foreign, "ERR MISMATCH health\n") == 0 &&
          strstr(status, " 9 WAIT_B_HEALTH ") != NULL,
        "stale or foreign health reports leave WAIT_B_HEALTH untouched (got %s / %s / %s)\n",
        stale, foreign, status);
}

static void test_b_health_claiming_another_schema_is_mismatch(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024];
    reach_wait_b_health(a_sha, b_sha);
    unsigned char database[128];
    flow_database(database, 16);
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    flow_sqlite_answer(16, 1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 16 OK 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(reply, "ERR MISMATCH health\n") == 0 &&
          strstr(status, " 9 WAIT_B_HEALTH ") != NULL,
        "B health at a schema other than B's signed schema is a mismatch (got %s / %s)\n",
        reply, status);
}

static void test_refusal_before_wait_a_refusal_is_stale(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024];
    reach_wait_b_health(a_sha, b_sha);
    snprintf(command, sizeof command,
        "GUARDREFUSAL %s 9 %s A %s 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE",
        SESSION, boot_nonce, a_sha);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(reply, "ERR STALE refusal\n") == 0 &&
          strstr(status, " 9 WAIT_B_HEALTH ") != NULL,
        "an A refusal while B health is pending is stale (got %s / %s)\n", reply, status);
}

static void test_refusal_holds_when_b_is_not_installed(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024];
    reach_wait_a_refusal(a_sha, b_sha);
    write_file_mode(INSTALLED_APK, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 0600);
    snprintf(command, sizeof command,
        "GUARDREFUSAL %s 12 %s A %s 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE",
        SESSION, boot_nonce, a_sha);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(reply, "ERR HOLD refusal\n") == 0 &&
          strstr(status, " 12 WAIT_A_REFUSAL ") != NULL,
        "an A refusal is held while exact B is no longer installed (got %s / %s)\n",
        reply, status);
    write_file_mode(INSTALLED_APK, FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, 0600);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDREFUSAL 13 A_REFUSED\n") == 0,
        "the same refusal is accepted once exact B is installed again (got %s)\n", reply);
}

static void test_restore_before_a_refused_is_stale(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024], expected[1024];
    reach_wait_a_refusal(a_sha, b_sha);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command, "GUARDACTION %s 12 RESTORE_PREMIGRATE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 12 WAIT_A_REFUSAL %s %s B %s 569 15 2 NONE NONE ",
        SESSION, boot_nonce, b_sha);
    CHECK(strcmp(reply, "ERR STALE restore\n") == 0 &&
          strncmp(status, expected, strlen(expected)) == 0,
        "a premigrate restore before the A refusal is stale and records nothing (got %s / %s)\n",
        reply, status);
}

static void test_finalize_before_a_healthy_is_stale(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024];
    reach_wait_a_health(a_sha, b_sha);
    snprintf(command, sizeof command, "GUARDACTION %s 16 FINALIZE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(reply, "ERR STALE finalize\n") == 0 &&
          strstr(status, " 16 WAIT_A_HEALTH ") != NULL && strstr(status, "CANARY_PASSED") == NULL,
        "FINALIZE before A health is proven is stale (got %s / %s)\n", reply, status);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 16 %s A %s 568 14 OK 2 %s %s ABSENT RESTORED",
        SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    snprintf(command, sizeof command, "GUARDACTION %s 17 FINALIZE", SESSION);
    dispatch_once(command, status, sizeof status);
    CHECK(strcmp(reply, "OK GUARDHEALTH 17 A_HEALTHY\n") == 0 &&
          strcmp(status, "OK GUARDACTION 18 FINALIZED\n") == 0,
        "the same arrangement finalizes once A health is proven (got %s / %s)\n", reply, status);
}

static void test_finalize_holds_when_installed_apk_is_not_exact_a(void) {
    char a_sha[65], b_sha[65], command[1024], reply[256], status[1024];
    reach_rollback_db_prepared(a_sha, b_sha);
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_LAUNCH_A &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
        "finalize fixture restores baseline and relaunches A\n");
    flow_sqlite_answer(14, 0);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 11 %s A %s 568 14 OK 2 %s %s ABSENT BASELINE",
        SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 12 A_HEALTHY\n") == 0,
        "finalize fixture reaches A_HEALTHY (got %s)\n", reply);
    write_file_mode(INSTALLED_APK, "replaced-behind-guard", 21, 0600);
    snprintf(command, sizeof command, "GUARDACTION %s 12 FINALIZE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    CHECK(strcmp(reply, "ERR HOLD finalize\n") == 0 &&
          strstr(status, " 12 A_HEALTHY ") != NULL && strstr(status, " PM_REJECTED NONE ") != NULL,
        "FINALIZE holds when the installed package is no longer exact A (got %s / %s)\n",
        reply, status);
}

static void test_rollback_with_unknown_installed_package_is_ambiguous(void) {
    char a_sha[65], b_sha[65], reply[1024];
    const char *b_payload;
    size_t b_bytes;
    pid_t executor = -1;
    reach_submitted_b_for_pm_result(a_sha, b_sha, &b_payload, &b_bytes, &executor);
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 1 << 8) == GUARD_WORK_NONE,
        "unknown-package fixture records the PM rejection\n");
    write_file_mode(INSTALLED_APK, "unknown-installed-package", 25, 0600);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(work == GUARD_WORK_NONE && strstr(reply, " 10 AMBIGUOUS ") != NULL &&
          strstr(reply, " PACKAGE_UNKNOWN AMBIGUOUS ") != NULL &&
          sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 0,
        "rollback over an unknown installed package settles ambiguous without installing A "
        "(got %d / %s)\n", work, reply);
}

static void test_rollback_restore_refuses_unrecognised_primary(void) {
    char a_sha[65], b_sha[65], reply[1024];
    reach_rollback_db_prepared(a_sha, b_sha);
    unsigned char foreign[128];
    flow_database(foreign, 14);
    foreign[100] = 0x5a;
    write_file_mode(DB_PATH, foreign, sizeof foreign, 0600);
    int work = guard_maintenance_supervisor_tick();
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(work == -1 && file_equals(DB_PATH, foreign, sizeof foreign) &&
          strstr(reply, " 10 ROLLBACK_DB_PREPARED ") != NULL,
        "rollback never overwrites an unrecognised live primary (got %d / %s)\n", work, reply);
}

static void test_rollback_restore_never_promotes_non_durable_copy(void) {
    char a_sha[65], b_sha[65], reply[1024];
    reach_rollback_db_prepared(a_sha, b_sha);
    unsigned char b_primary[128];
    flow_database(b_primary, 15);
    write_file_mode(DB_PATH, b_primary, sizeof b_primary, 0600);
    guard_test_set_fault(GUARD_TEST_FAULT_DB_AFTER_ASIDE);
    int work = guard_maintenance_supervisor_tick();
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(work == -1 && file_equals(DB_PATH, b_primary, sizeof b_primary) &&
          access(DB_DIR "/.guard.rollback.tmp", F_OK) != 0 &&
          strstr(reply, " 10 ROLLBACK_DB_PREPARED ") != NULL,
        "a non-durable baseline copy never replaces the live primary (got %d / %s)\n",
        work, reply);
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_LAUNCH_A,
        "a durable retry of the same restore relaunches A\n");
}

static void test_cancel_refuses_armed_plan_and_keeps_custody(void) {
    char a_sha[65], b_sha[65], command[256], reply[256], status[1024];
    reach_prepared_at(0, a_sha, b_sha);
    snprintf(command, sizeof command, "GUARDCANCEL %s 7", SESSION);
    dispatch_once(command, reply, sizeof reply);
    dispatch_once("GUARDSTATUS", status, sizeof status);
    static const char a_payload[] = "boundary-installed-a";
    static const char b_payload[] = "boundary-candidate-b";
    CHECK(strcmp(reply, "ERR ARMED cancel\n") == 0 &&
          file_equals(CUSTODY "/a.apk", a_payload, sizeof a_payload - 1) &&
          file_equals(CUSTODY "/b.apk", b_payload, sizeof b_payload - 1) &&
          strstr(status, " 7 PREPARED ") != NULL,
        "cancel refuses an armed plan and keeps its custody (got %s / %s)\n", reply, status);
}

static void test_cancel_refuses_inflight_capture(void) {
    setup();
    unsigned char database[128];
    flow_database(database, 14);
    char db_sha[65], a_sha[65], b_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    hash_bytes(FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, a_sha);
    hash_bytes(FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof FLOW_A_PAYLOAD - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof FLOW_B_PAYLOAD - 1, 3);
    stream_role("A", 3, a_sha, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 4);
    stream_role("B", 4, b_sha, FLOW_B_PAYLOAD, sizeof FLOW_B_PAYLOAD - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, FLOW_A_PAYLOAD, sizeof FLOW_A_PAYLOAD - 1, 0600);
    guard_test_set_supervised(1);
    guard_test_set_fault(GUARD_TEST_FAULT_MANIFEST_FILE_SYNC);
    char command[256], cut[256], reply[256];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, cut, sizeof cut);
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    snprintf(command, sizeof command, "GUARDCANCEL %s 6", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(cut, "ERR INDETERMINATE capture\n") == 0 &&
          strcmp(reply, "ERR ARMED cancel\n") == 0 &&
          access(CUSTODY "/draft.v1", F_OK) == 0 &&
          access(CUSTODY "/a.apk", F_OK) == 0 &&
          access(CUSTODY "/capture.v1", F_OK) == 0,
        "cancel refuses a draft whose capture is in flight (got %s / %s)\n", cut, reply);
}

static void test_forward_deadline_tick_enters_rollback(void) {
    char a_sha[65], b_sha[65], reply[1024];
    reach_prepared_at(100000, a_sha, b_sha);
    guard_maintenance_set_supervisor_owner();
    guard_test_set_now_ms(520000);
    int work = guard_maintenance_supervisor_tick();
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    const char *const install_b[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    CHECK(work == GUARD_WORK_NONE && strstr(reply, " 8 ROLLBACK_REQUIRED ") != NULL &&
          strstr(reply, " FORWARD_TIMEOUT NONE ") != NULL &&
          sysexec_stub_count_argv("/system/bin/pm", install_b, 1) == 0,
        "the forward deadline turns a forward phase into rollback (got %d / %s)\n", work, reply);
    guard_test_set_now_ms(0);
}

// The first reply to an INSTALLSTREAM, from a worker thread: an admitted upload answers READY and then
// blocks on its payload, so the client end is shut afterwards and the worker ends on the short body.
static void installstream_first_reply(char *reply, size_t capacity) {
    dispatch_job job;
    pthread_t thread;
    int peer[2];
    start_dispatch(&job, &thread, peer, "INSTALLSTREAM 5");
    read_line(peer[1], reply, capacity);
    shutdown(peer[1], SHUT_WR);
    finish_dispatch(thread, peer[1]);
}

static void test_package_mutation_refused_while_guard_draft_exists(void) {
    setup();
    prepare();
    char reply[256], command[256];
    installstream_first_reply(reply, sizeof reply);
    CHECK(strcmp(reply, "BUSY\n") == 0,
        "package mutation is refused while a Guard draft exists (got %s)\n", reply);
    snprintf(command, sizeof command, "GUARDCANCEL %s 1", SESSION);
    dispatch_once(command, reply, sizeof reply);
    installstream_first_reply(reply, sizeof reply);
    CHECK(strcmp(reply, "READY\n") == 0,
        "package mutation is admitted once Guard is empty again (got %s)\n", reply);
}

int main(void) {
    exec_from_canonical_helper();
    signal(SIGPIPE, SIG_IGN);
    test_health_rejects_stale_and_foreign_reports();
    test_b_health_claiming_another_schema_is_mismatch();
    test_refusal_before_wait_a_refusal_is_stale();
    test_refusal_holds_when_b_is_not_installed();
    test_restore_before_a_refused_is_stale();
    test_finalize_before_a_healthy_is_stale();
    test_finalize_holds_when_installed_apk_is_not_exact_a();
    test_rollback_with_unknown_installed_package_is_ambiguous();
    test_rollback_restore_refuses_unrecognised_primary();
    test_rollback_restore_never_promotes_non_durable_copy();
    test_cancel_refuses_armed_plan_and_keeps_custody();
    test_cancel_refuses_inflight_capture();
    test_forward_deadline_tick_enters_rollback();
    test_package_mutation_refused_while_guard_draft_exists();
    guard_test_reset();
    (void)unlink(BOOT_FILE);
    if (failures) {
        printf("%d guard maintenance gate test(s) failed\n", failures);
        return 1;
    }
    puts("guard maintenance gate tests passed");
    return 0;
}
