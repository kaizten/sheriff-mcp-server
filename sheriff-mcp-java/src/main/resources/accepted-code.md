## JavaJavaDocCommentInMethodMessage1 JavaJavaDocCommentInMethodMessage2 JavaJavaDocCommentInMethodMessage3 JavaJavaDocCommentInMethodMessage4 JavaJavaDocCommentInMethodMessage5 JavaJavaDocCommentInMethodMessage6 JavaJavaDocFormatMessage1 JavaJavaDocFormatMessage2 JavaJavaDocFormatMessage3
Every method has a JavaDoc right above it, `/**` and `*/` on lines of their own, a sentence, one `@param` per parameter and an `@return` unless it returns void, none of them empty:

    /**
     * Returns the owner at a page.
     *
     * @param page the page, counted from one
     * @param model where to put the owner
     * @return the owner
     */
    public String find(int page, Map<String, String> model) {

## JavaJavaDocCommentInConstructorMissing JavaDocCommentInConstructorIsEmpty JavaDocCommentInConstructorNoParam JavaJavaDocCommentInConstructorParamEmtpy
Every constructor has a JavaDoc the same way, with one `@param` per parameter:

    /**
     * Creates a finder over a list of owners.
     *
     * @param owners the owners to search
     */
    public OwnerFinder(List<String> owners) {

## HardcodedStringInMethodCall ThrowStatementWithHardcodedString HardcodedStringInException JavaSwitchSentenceMessage1
A text is declared once, as a `private static final` constant at the top of the class, and used by its name; a constant that already holds the same text is reused:

    private static final String OWNER = "owner";
    private static final String UNKNOWN = "Unknown owner";
    ...
    model.put(OWNER, owner);
    throw new IllegalArgumentException(UNKNOWN);

## JavaHardcodedValueInComparisonMessage1
A number compared against is a constant too, named for what it means:

    private static final int FIRST_PAGE = 1;
    ...
    if (page < FIRST_PAGE) {

## JavaLineCommentsChecker JavaBlockComments JavaDocCommentsInMethod
Delete the comment. If it says something worth keeping, write it as a sentence of the JavaDoc of the method or field it was in or above, never as a comment inside a method.

## JavaOrphanJavaDocComments
A JavaDoc goes right above what it documents, with no blank line between them. Delete the blank line, or delete a JavaDoc that documents nothing.

## JavaBracesIfStatementsMessage1 JavaBracesIfStatementsMessage2 JavaBracesIfStatementsMessage3 BracesWhileStatements BracesForStatements
Every branch and loop body has braces, and its content starts on a line of its own:

    if (page < FIRST_PAGE) {
        throw new IllegalArgumentException(UNKNOWN);
    }

## JavaEqualsMethodOverrideMessage1 JavaHashCodeMethodOverrideMessage1 JavaToStringMethodMessage1 JavaEqualsMethodArgumentName
`equals`, `hashCode` and `toString` carry `@Override`, and the argument of `equals` is called `otherObject`:

    @Override
    public boolean equals(Object otherObject) {

## StaticFinalAttribute
`static` comes before `final`:

    private static final int LIMIT = 10;

## UtilityClass UtilityClassThrowException
A class with only static members has a private constructor that throws, with its message in a constant:

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private Names() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

## SentencePositionFirstLine SentencePositionLastLine
A method's first and last statements are on lines of their own, never on the line of its braces:

    /**
     * Returns how many owners there are.
     *
     * @return that number
     */
    public int size() {
        return owners.size();
    }

## JavaEmptyLineBetweenPackageMessage1 JavaEmptyLineBetweenPackageMessage2
One empty line after the `package` declaration, and one after the last import.
