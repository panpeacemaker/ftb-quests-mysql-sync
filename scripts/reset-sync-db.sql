-- reset-sync-db.sql
-- Destructive: wipes all ftb-quests-mysql-sync state for a clean from-scratch test.
-- RUN ONLY when BOTH game servers are STOPPED and AFTER taking a mysqldump backup.
-- Usage:
--   mysqldump <database> > backup-pre-reset.sql
--   mysql <database> < scripts/reset-sync-db.sql
-- Tables are TRUNCATEd (kept), so schema + migrations stay intact.

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE ftbquests_teamdata;
TRUNCATE TABLE ftbquests_reward_claim_scopes;
TRUNCATE TABLE ftbquests_reward_claims;
TRUNCATE TABLE ftbquests_team_info;
TRUNCATE TABLE ftbquests_team_membership;
TRUNCATE TABLE ftbquests_rank_progress;
TRUNCATE TABLE ftbchunks_team_claims;
TRUNCATE TABLE ftbquests_player_names;
TRUNCATE TABLE ftbquestssync_meta;

-- ftbquests_reset_audit exists on production but is not in the table-creation set;
-- guard the truncate so the script also works on installs that lack it.
SET @has_audit = (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ftbquests_reset_audit');
SET @sql = IF(@has_audit > 0, 'TRUNCATE TABLE ftbquests_reset_audit', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET FOREIGN_KEY_CHECKS = 1;
