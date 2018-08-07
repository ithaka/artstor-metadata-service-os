
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('obj1', 10, 0,
                                                                      'thumbnail1','thumbnail2',
                                                                      'thumbnail3','list_image',
                                                                      'thumb_image', '');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('obj2', 20, 1,
                                                                      '2_thumbnail1',
                                                                      '2_thumbnail2',
                                                                      null,
                                                                      '2_list_image',
                                                                      '2_thumb_image',
                                                                      'obj100');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('obj3', 10, 1,
                                                                      '3_thumbnail1',
                                                                      '3_thumbnail2',
                                                                      '3_thumbnail3',
                                                                      '3_list_image',
                                                                      '3_thumb_image',
                                                                      '');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('obj4', 10, 1,
                                                                      '4_thumbnail1',
                                                                      '4_thumbnail2',
                                                                      '4_thumbnail3',
                                                                      '4_list_image',
                                                                      '4_thumb_image',
                                                                      '');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                           thumbnail_img_url, cf_object_id) VALUES ('SS33731_33731_1094662', 10, 1,
                                                                    '5_thumbnail1',
                                                                    '5_thumbnail2',
                                                                    '5_thumbnail3',
                                                                    '5_list_image',
                                                                    '5_thumb_image',
                                                                    '');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('AWSS35953_35953_35410117', 10, 1,
                                                                      '6_thumbnail1',
                                                                      '6_thumbnail2',
                                                                      '6_thumbnail3',
                                                                      '6_list_image',
                                                                      'imgstor/size0/sslps/c35953/10707836.jpg',
                                                                      '');
INSERT INTO data_object_gen (object_id, object_type_id, clustered, thumbnail1, thumbnail2, thumbnail3, list_img_url,
                             thumbnail_img_url, cf_object_id) VALUES ('LESSING_ART_10310752347', 10, 1,
                                                                      '7_thumbnail1',
                                                                      '7_thumbnail2',
                                                                      '7_thumbnail3',
                                                                      '7_list_image',
                                                                      'imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg',
                                                                      '');
INSERT INTO data_object (object_id, object_type_id, icc_profile_loc, object_xml, collection_id) VALUES ('obj1',
                                                                         10,
                                                                         '/a/path/to/file',
                                                                         '<xml><data>here</data></xml>',
                                                                         '1000');

INSERT INTO data_object (object_id, object_type_id, icc_profile_loc, object_xml, collection_id) VALUES ('SS33731_33731_1094662',
                                                                         10,
                                                                         '/a/path/to/file',
                                                                         '<xml><data>here</data></xml>',
                                                                         '1000');
INSERT INTO data_object (object_id, object_type_id, icc_profile_loc, object_xml) VALUES ('AWSS35953_35953_35410117',
                                                                                         10,
                                                                                         '/a/path/to/file',
                                                                                         '<Image><Name>"Goofy"</Name><SSID>10707836</SSID></Image>');
INSERT INTO data_object (object_id, object_type_id, icc_profile_loc, object_xml) VALUES ('LESSING_ART_10310752347',
                                                                                         10,
                                                                                         '/a/path/to/file',
                                                                                         '<Image><Name>NoSSID</Name><SSID></SSID></Image>');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('obj1',
                                                                    '{"json": true}');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('flush_obj1',
                                                                    '{"json": true}');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('flush_obj2',
                                                                    '{"json": true}');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('SS33731_33731_1094662',
                                                                    '{"json": true}');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('AWSS35953_35953_35410117',
                                                                    '[{"count":1,"fieldName":"Creator","fieldValue":"Sara Woster","index":1},{"count":1,"fieldName":"Title","fieldValue":"Horse"},{"count":1,"fieldName":"Work Type","fieldValue":"painting"}]');
INSERT INTO data_object_metadata (object_id, metadata_json) VALUES ('LESSING_ART_10310752347',
  '[{"count":1, "fieldName":"Creator", "fieldValue":"Leonardo da Vinci (1452-1519)", "index":1}, {"count":1, "fieldName":"Title","fieldValue":"Lady with an Ermine"}, {"count":1, "fieldName":"Work Type", "fieldValue":"painting"}]');

INSERT INTO image_file_mapping (file_name, resolution, image_url, width, height, resolution_x, resolution_y) VALUES
('obj1', 5, '/path/to/image', 1024, 768, 600, 600);

INSERT INTO image_file_mapping (file_name, resolution, image_url, width, height, resolution_x, resolution_y) VALUES
('SS33731_33731_1094662', 5, '/path/to/image', 1024, 768, 600, 600);

INSERT INTO collectionidimagemap VALUES ('obj1', '1000');
INSERT INTO collectionidimagemap VALUES ('obj2', '2000');
INSERT INTO collectionidimagemap VALUES ('obj3', '1000');
INSERT INTO collectionidimagemap VALUES ('obj4', '1000');
INSERT INTO collectionidimagemap VALUES ('SS33731_33731_1094662', '1000');
INSERT INTO collectionidimagemap VALUES ('AWSS35953_35953_35410117', '1000');
INSERT INTO collectionidimagemap VALUES ('LESSING_ART_10310752347','1000');
INSERT INTO collectionaccesslookup VALUES ('1000', 2, 1000);
INSERT INTO collectionaccesslookup VALUES ('2000', 1, 2000);
INSERT INTO user_profile VALUES(100, 1000);
INSERT INTO user_profile VALUES(299277, 1000);
INSERT INTO user_profile VALUES(123456, 1000);
INSERT INTO user_profile VALUES(200, 2000);

INSERT INTO collections values ('1000', 'Test name 1' ,2);
INSERT INTO collections values ('2000', 'Test name 2' ,1);
