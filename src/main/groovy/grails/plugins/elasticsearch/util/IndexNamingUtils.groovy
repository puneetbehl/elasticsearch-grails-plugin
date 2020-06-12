package grails.plugins.elasticsearch.util

/**
 * Created by marcoscarceles on 16/05/2016.
 */
class IndexNamingUtils {

    public static final READ_SUFFIX = "_read"
    public static final WRITE_SUFFIX = "_write"

    static String queryingIndexFor(String indexName) {
        indexName + READ_SUFFIX
    }

    static String indexingIndexFor(String indexName) {
        indexName + WRITE_SUFFIX
    }
}
