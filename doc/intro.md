# Introduction to artstor-metadata-service-os

Artstor metadata service provides short and complete metadata for content items published to AIW platform.

Requirements:
1. Solr or a search engine that provides metadata of Artstor Core fields and Key-value pairs.

2. Oracle database as an alternative to Solr/search engine.

3. An implementation wrapper for Authentication wrapper `with-auth` if required to authenticate access rights for content.

Library Dependencies:-
1. org.slf4j and ring logger libraries
2. Ithaka platform libraries needed for calling Solr/Search service.
3. Sql libraries yesql and ojdbc7
4. ragtime for setting up test database.
5. web api and swagger done using metosin/compojure.
