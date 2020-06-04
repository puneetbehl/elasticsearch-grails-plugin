package test.all

/**
 * @author <ahref='mailto:donbeave@gmail.com' > Alexey Zhokhov</a>
 */
class Post {

    String subject
    String body

    static searchable = {
        subject analyzer: 'test_analyzer', search_analyzer: 'standard'
        body analyzer: 'test_analyzer', search_analyzer: 'standard'
    }
}
