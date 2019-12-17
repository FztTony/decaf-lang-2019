package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：cannot instantiate abstract class 'zig'<br>
 * PA2
 */
public class NewAbstractClassError extends DecafError {

    private String name;

    public NewAbstractClassError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() { return "cannot instantiate abstract class '" + name + "'"; }

}
