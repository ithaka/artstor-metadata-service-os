-- Ensure this is H2
CALL 5*5;

DROP TABLE IF EXISTS data_object_gen;
create table data_object_gen (
  object_id varchar(64) NOT NULL PRIMARY KEY,
  object_type_id INTEGER NOT NULL,
  clustered INTEGER NOT NULL DEFAULT 0,
  thumbnail1 VARCHAR(64),
  thumbnail2 VARCHAR(64),
  thumbnail3 VARCHAR(64),
  list_img_url VARCHAR(256),
  download_size VARCHAR(256),
  thumbnail_img_url VARCHAR(256),
  cf_object_id VARCHAR(64)
);

DROP TABLE IF EXISTS data_object;
create table data_object (
  object_id varchar(64) NOT NULL PRIMARY KEY,
  object_type_id INTEGER NOT NULL,
  icc_profile_loc varchar(64),
  object_xml CLOB,
  collection_id varchar(64)
);

DROP TABLE IF EXISTS data_object_metadata;
create table data_object_metadata (
  object_id varchar(64) NOT NULL PRIMARY KEY,
  metadata_json varchar(1024)
);

DROP TABLE IF EXISTS image_file_mapping;
create table image_file_mapping (
  file_name varchar(64) NOT NULL PRIMARY KEY,
  resolution INTEGER,
  image_url varchar(64),
  width INTEGER,
  height INTEGER,
  resolution_x INTEGER,
  resolution_y INTEGER
);

-- Table to hold map items to collections
DROP TABLE IF EXISTS collectionidimagemap;
create table collectionidimagemap (
  object_id varchar(64) NOT NULL PRIMARY KEY,
  collectionid VARCHAR(64) NOT NULL
);

-- Table to hold map items to collections
DROP TABLE IF EXISTS collectionaccesslookup;
create table collectionaccesslookup (
  collectionid varchar(64) NOT NULL,
  collection_type INTEGER NOT NULL,
  institution_id INTEGER NOT NULL
);

-- Table to hold map items to collections
DROP TABLE IF EXISTS coll_filter_ig;
create table coll_filter_ig (
  object_id varchar(64) not null,
  cf_object_id varchar(64) not null,
  cnt_object INTEGER
);

-- Table to hold user profile information
DROP TABLE IF EXISTS user_profile;
create table user_profile (
  profileid INTEGER not null PRIMARY KEY,
  institution_id INTEGER NOT NULL
);

-- Table to simulate the iplookup table in Oracle DB
DROP TABLE IF EXISTS iplookup;
create table iplookup (
	ip_address varchar(20) not null,
	institution_id number not null,
	allow_ip integer
);

-- Table to simulate the collections table in Oracle DB
DROP TABLE IF EXISTS collections;
create table collections (
	collectionid varchar(64) NOT NULL,
    collectionname varchar(255),
    collection_type  integer NOT NULL
);