package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * string is not a callable type.
 */
public class NotCallableTypeError extends DecafError {

    private String name;

    public NotCallableTypeError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return name + " is not a callable type";
    }

}

