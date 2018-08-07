-- name: sql-retrieve-item
select dog.object_id as objectId,
  dog.object_type_id as objectTypeId,
  dog.clustered,
  dog.thumbnail1,
  dog.thumbnail2,
  dog.thumbnail3,
  dog.list_img_url as largeImgUrl,
  dog.thumbnail_img_url as thumbnailImgUrl,
  dog.cf_object_id as cfObjectId,
  dog.download_size as downloadSize,
  do.collection_id as collectionId,
  col.collection_type as collectionType
from data_object_gen dog,
  data_object do,
  collections col
where
  dog.object_id = do.object_id
  and do.collection_id = col.collectionid
  and dog.object_id = :object_id;

-- name: sql-retrieve-items
select dog.object_id as objectId,
  dog.object_type_id as objectTypeId,
  dog.clustered,
  dog.thumbnail1,
  dog.thumbnail2,
  dog.thumbnail3,
  dog.list_img_url as largeImgUrl,
  dog.thumbnail_img_url as thumbnailImgUrl,
  dog.cf_object_id as cfObjectId,
  dog.download_size as downloadSize,
  do.collection_id as collectionId,
  col.collection_type as collectionType
from data_object_gen dog,
  data_object do,
  collections col
where
  dog.object_id = do.object_id
  and do.collection_id = col.collectionid
  and dog.object_id in (:object_ids);

-- name: sql-get-metadata
-- Assemble all the bits of metadata ORACLE SPECIFIC SQL
-- WARNING .getClobVal is Oracle specific. Changes to this SQL will not be unit tested using H2 database
select do.object_id,
       do.icc_profile_loc,
       do.collection_id,
       col.collectionname as collection_name,
       col.collection_type,
       do.object_type_id,
       dog.download_size,
       dog.thumbnail_img_url as thumbnailImgUrl,
       dom.metadata_json,
       do.object_xml.getClobVal() as xml_data,
       ifm.image_url,
       ifm.width,
       ifm.height,
       ifm.resolution_x,
       ifm.resolution_y
from data_object do,
     data_object_gen dog,
     data_object_metadata dom,
     image_file_mapping ifm,
     collections col
where dom.object_id = do.object_id
  and dog.object_id = do.object_id
  and ifm.file_name = do.object_id
  and do.collection_id = col.collectionid
  and ifm.resolution = 5
  and do.object_id in (:object_ids);

-- name: sql-get-metadata-without-backslashes
select object_id,
      regexp_replace(metadata_json, '\\', '') as metadata_json
from  data_object_metadata
where object_id = :object_id;

-- name: sql-flush-metadata!
-- Flush Ojbect form data_ all the bits of metadata
DELETE FROM DATA_OBJECT_METADATA
WHERE OBJECT_ID in (:object_ids)

-- name: sql-get-qtvr-asset-image-url
select image_url from image_file_mapping ifm where ifm.resolution = 25 and ifm.image_id = :object_id;

--name: sql-get-adl-category-id
select ct.category_id
from category_tree ct
where ct.collection_id = :collection_id
  and ct.parent_id = 0
  and ct.category_id in
    (select co.category_id from category_object co where co.object_id = :object_id and co.collection_id = ct.collection_id);



