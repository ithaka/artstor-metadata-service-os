-- name: get-user-institution-id
-- This call is to the Oracle database
select institution_id from user_profile where profileid = :profile_id;
