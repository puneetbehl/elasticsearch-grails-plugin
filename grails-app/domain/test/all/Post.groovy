package test.all

/**
 * @author <a href='mailto:donbeave@gmail.com'>Alexey Zhokhov</a>
 */
class Post {

    String subject
    String body

    static searchable = {
        subject analyzer: 'test_analyzer'
        body analyzer: 'test_analyzer'
    }

}
